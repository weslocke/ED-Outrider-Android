package io.github.weslocke.outrider

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * The wake word: "OK / Okay / Hey / Hello <word>" (the word is a setting, "Vespa" by default), spotted on the tablet
 * by sherpa-onnx's keyword spotter (no audio leaves the tablet). Runs only while the app is on screen. On a match it
 * stops listening (frees the microphone for the speech recognizer) and calls [onWake] on the main thread.
 */
class WakeWord(private val assets: AssetManager, private val onWake: () -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var spotter: KeywordSpotter? = null   // made and used on the audio thread only
    private val main = Handler(Looper.getMainLooper())

    val active: Boolean get() = running

    /** Starts listening for [keywords] (from [WakePhrases.keywords]); a running listener is restarted. */
    @SuppressLint("MissingPermission")   // the caller checks RECORD_AUDIO first
    fun start(keywords: String) {
        stop()
        running = true
        thread = Thread({ listen(keywords) }, "wake-word").apply { start() }
    }

    /** Stops listening and waits for the microphone to be released. */
    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    /** Frees the model (call when the app closes). */
    fun release() {
        stop()
        spotter?.release()
        spotter = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(keywords: String) {
        val kws = spotter ?: try {
            KeywordSpotter(assets, config()).also { spotter = it }
        } catch (e: Throwable) {
            Log.e(TAG, "keyword spotter failed to load", e)
            running = false
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
        } catch (e: Exception) {
            Log.e(TAG, "microphone unavailable", e)
            running = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running = false
            return
        }
        val stream = kws.createStream(keywords)
        val pcm = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(pcm, 0, CHUNK)
                if (n <= 0) continue
                for (i in 0 until n) samples[i] = pcm[i] / 32768f
                stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                while (running && kws.isReady(stream)) {
                    kws.decode(stream)
                    val keyword = kws.getResult(stream).keyword
                    if (keyword.isNotEmpty()) {
                        Log.i(TAG, "heard $keyword")
                        kws.reset(stream)
                        running = false
                        main.post(onWake)
                    }
                }
            }
        } finally {
            rec.stop()
            rec.release()
            stream.release()
        }
    }

    private fun config() = KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = "kws/encoder.onnx", decoder = "kws/decoder.onnx", joiner = "kws/joiner.onnx"),
            tokens = "kws/tokens.txt",
            numThreads = 1,
            provider = "cpu",
        ),
        // a placeholder the library insists on; the real phrases come with each stream
        keywordsFile = "kws_default.txt",
        // the phrases compete in one search: 4 paths (the default) lost most "OK Vespa"s to "Hey"/"Hello";
        // 16 caught 16-19 of 24 test clips instead of 9, for a few more false alarms on "Hey Vesper"
        maxActivePaths = 16,
    )

    private companion object {
        const val TAG = "WakeWord"
        const val RATE = 16000
        const val CHUNK = 1600   // 100 ms
    }
}

/** The wake phrases and their tuning: pure, unit-tested. */
object WakePhrases {
    const val DEFAULT_WORD = "Vespa"
    const val MAX_WORD = 24
    private val PREFIXES = listOf("OK", "OKAY", "HEY", "HELLO")

    enum class Sensitivity(val label: String, val boost: Float, val threshold: Float) {
        LOW("Low", 1.0f, 0.30f),
        NORMAL("Normal", 1.5f, 0.20f),
        HIGH("High", 2.5f, 0.12f);

        fun next(): Sensitivity = entries[(ordinal + 1) % entries.size]
    }

    /** Why [word] can't be a wake word, or null when it can. */
    fun problem(bpe: Bpe, tokens: Set<String>, word: String): String? {
        val clean = Bpe.clean(word)
        return when {
            clean.isEmpty() -> "Type a word, e.g. Vespa"
            clean.length > MAX_WORD -> "Keep it under $MAX_WORD letters"
            !bpe.known(bpe.encode("HEY $clean"), tokens) -> "The model can't spell that: use plain letters"
            else -> null
        }
    }

    /** The keyword spotter's phrase list: one line per prefix, "/"-separated, each boosted per [sensitivity]. */
    fun keywords(bpe: Bpe, word: String, sensitivity: Sensitivity): String {
        val clean = Bpe.clean(word)
        return PREFIXES.joinToString("/") { prefix ->
            bpe.encode("$prefix $clean").joinToString(" ") +
                " :${sensitivity.boost} #${sensitivity.threshold} @${prefix}_${clean.replace(' ', '_')}"
        }
    }
}
