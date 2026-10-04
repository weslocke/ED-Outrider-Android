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
 * by sherpa-onnx's keyword spotter (no audio leaves the tablet). Runs only while the app is on screen.
 *
 * Each [start] is a run with its own token: a thread from an older run stops at its next check and never reports.
 * Events come on the main thread: [Event.Listening] once the microphone is really delivering audio, [Event.Heard] on
 * a match (the run has then ended and freed the microphone for the speech recognizer), [Event.Failed] when the
 * model or the microphone can't be used.
 */
class WakeWord(private val assets: AssetManager, private val onEvent: (Event) -> Unit) {

    sealed class Event {
        object Listening : Event()
        data class Heard(val keyword: String) : Event()
        data class Failed(val reason: String) : Event()
    }

    /** The current run's token; 0 = stopped. */
    @Volatile private var current = 0
    private var lastRun = 0
    private var thread: Thread? = null
    private val modelLock = Any()
    private var spotter: KeywordSpotter? = null   // made once, under modelLock
    private val main = Handler(Looper.getMainLooper())

    val active: Boolean get() = current != 0

    /** Starts listening for [keywords] (from [WakePhrases.keywords]); a running listener is replaced. */
    @SuppressLint("MissingPermission")   // the caller checks RECORD_AUDIO first
    fun start(keywords: String) {
        val previous = thread
        stop()
        val run = ++lastRun
        current = run
        // a previous thread still releasing the microphone is waited for inside the new one, not here
        thread = Thread({ listen(run, keywords, previous) }, "wake-word").apply { start() }
    }

    /** Stops listening. The thread lets go of the microphone at its next read (at most ~100 ms). */
    fun stop() {
        current = 0
        thread?.join(STOP_WAIT_MS)
        thread = null
    }

    /** Frees the model (call when the app closes). */
    fun release() {
        stop()
        synchronized(modelLock) {
            spotter?.release()
            spotter = null
        }
    }

    private fun report(run: Int, event: Event) {
        // delivered only while that run is still the current one (a Heard ends the run on purpose)
        main.post { if (run == current || (event is Event.Heard && run == lastRun)) onEvent(event) }
    }

    private fun fail(run: Int, reason: String) {
        if (run != current) return
        current = 0
        main.post { if (lastRun == run) onEvent(Event.Failed(reason)) }
    }

    @SuppressLint("MissingPermission")
    private fun listen(run: Int, keywords: String, previous: Thread?) {
        try {
            previous?.join(PREVIOUS_WAIT_MS)   // one recorder at a time
            if (run != current) return
            val kws = synchronized(modelLock) {
                spotter ?: KeywordSpotter(assets, config()).also { spotter = it }
            }
            val stream = kws.createStream(keywords)
            try {
                record(run, kws, stream)
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            // never let the audio thread take the app down
            Log.e(TAG, "wake word stopped", e)
            fail(run, "it stopped unexpectedly (${e.javaClass.simpleName})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(run: Int, kws: KeywordSpotter, stream: com.k2fsa.sherpa.onnx.OnlineStream) {
        val pcm = ShortArray(CHUNK)
        val samples = FloatArray(CHUNK)
        var restarts = 0
        var announced = false
        while (run == current) {
            val rec = openRecorder() ?: return fail(run, "the microphone is unavailable")
            try {
                rec.startRecording()
                read@ while (run == current) {
                    val n = rec.read(pcm, 0, CHUNK)
                    when (AudioReads.action(n)) {
                        AudioReads.Action.SAMPLES -> {}
                        AudioReads.Action.WAIT -> {
                            Thread.sleep(10)
                            continue@read
                        }
                        AudioReads.Action.REOPEN -> break@read   // the audio server restarted: a new recorder
                        AudioReads.Action.FAIL -> return fail(run, "the microphone stopped working (error $n)")
                    }
                    if (!announced) {
                        announced = true
                        report(run, Event.Listening)
                    }
                    for (i in 0 until n) samples[i] = pcm[i] / 32768f
                    stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), RATE)
                    while (run == current && kws.isReady(stream)) {
                        kws.decode(stream)
                        val keyword = kws.getResult(stream).keyword
                        if (keyword.isNotEmpty()) {
                            Log.i(TAG, "heard $keyword")
                            kws.reset(stream)
                            if (run == current) {
                                current = 0
                                report(run, Event.Heard(keyword))
                            }
                            return
                        }
                    }
                }
            } finally {
                try {
                    rec.stop()
                } catch (e: IllegalStateException) {
                    // never started
                }
                rec.release()
            }
            if (run != current) return
            if (++restarts > MAX_REOPENS) return fail(run, "the microphone keeps dropping out")
            Thread.sleep(500L * restarts)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // a second of slack, so a decode or GC pause doesn't lose audio
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2))
        } catch (e: Exception) {
            Log.e(TAG, "microphone unavailable", e)
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
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
        const val STOP_WAIT_MS = 1500L
        const val PREVIOUS_WAIT_MS = 5000L
        const val MAX_REOPENS = 3
    }
}

/** What to do with AudioRecord.read's result. Pure (the error codes are inlined), unit-tested. */
object AudioReads {
    enum class Action { SAMPLES, WAIT, REOPEN, FAIL }

    private const val ERROR_DEAD_OBJECT = -6   // AudioRecord.ERROR_DEAD_OBJECT: the audio server went away

    fun action(n: Int): Action = when {
        n > 0 -> Action.SAMPLES
        n == 0 -> Action.WAIT
        n == ERROR_DEAD_OBJECT -> Action.REOPEN
        else -> Action.FAIL   // ERROR, ERROR_BAD_VALUE, ERROR_INVALID_OPERATION: retrying would spin
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
