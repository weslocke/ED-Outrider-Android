package io.github.weslocke.outrider

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One spoken question at a time, through Android's speech recognizer: on-device when the tablet has it (no audio
 * leaves the tablet), the system's online recognizer otherwise. Started by the wake word or the Ask button.
 * Main thread only.
 */
class Listener(private val context: Context, private val onEvent: (Event) -> Unit) {

    sealed class Event {
        object Ready : Event()
        data class Partial(val text: String) : Event()
        data class Heard(val text: String) : Event()
        data class Failed(val reason: String) : Event()
    }

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false

    val active: Boolean get() = recognizer != null

    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onEvent(Event.Failed("This tablet has no speech recognition"))
            return
        }
        onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        begin()
    }

    private fun begin() {
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Event.Ready)
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { onEvent(Event.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                release()
                onEvent(if (text.isNullOrBlank()) Event.Failed(Speech.describeError(SpeechRecognizer.ERROR_NO_MATCH)) else Event.Heard(text))
            }
            override fun onError(error: Int) {
                release()
                // the on-device recognizer may lack this language's pack: the system's online one may have it
                if (onDevice && Speech.retryOnline(error)) {
                    onDevice = false
                    begin()
                } else onEvent(Event.Failed(Speech.describeError(error)))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // offline only on the on-device recognizer: the online fallback is there precisely because offline failed
        if (onDevice) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(intent)
    }

    fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
}

/** Pure helpers, unit-tested. */
object Speech {
    // SpeechRecognizer's codes (constants inlined: the stubs in unit tests have no values)
    private const val ERROR_NETWORK_TIMEOUT = 1
    private const val ERROR_NETWORK = 2
    private const val ERROR_AUDIO = 3
    private const val ERROR_SERVER = 4
    private const val ERROR_CLIENT = 5
    private const val ERROR_SPEECH_TIMEOUT = 6
    private const val ERROR_NO_MATCH = 7
    private const val ERROR_RECOGNIZER_BUSY = 8
    private const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    private const val ERROR_SERVER_DISCONNECTED = 11

    fun describeError(code: Int): String = when (code) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
        ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone isn't allowed: allow it in Android's settings for ED Outrider"
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED ->
            "Speech recognition needs the internet on this tablet (or Android's offline speech pack)"
        ERROR_AUDIO -> "The microphone didn't work"
        ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy; try again"
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> "Speech recognition has no pack for this language"
        ERROR_CLIENT -> "Listening stopped"
        else -> "Speech recognition failed ($code)"
    }

    /** On-device errors worth one more try with the system's own (online) recognizer. */
    fun retryOnline(code: Int): Boolean =
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE || code == ERROR_SERVER || code == ERROR_CLIENT

    enum class Mic { GRANTED, ASKABLE, BLOCKED }

    /**
     * The microphone permission's state. After a refusal Android only shows its dialog again while
     * [rationale] (shouldShowRequestPermissionRationale) is true; once it's false after asking, only Android's
     * settings can allow it.
     */
    fun micState(granted: Boolean, askedBefore: Boolean, rationale: Boolean): Mic = when {
        granted -> Mic.GRANTED
        askedBefore && !rationale -> Mic.BLOCKED
        else -> Mic.ASKABLE
    }

    /** What the overlay says once Outrider has answered. */
    fun answerLine(a: AskAnswer): String = when {
        a.answer.isNotBlank() -> a.answer
        a.matched == "none" -> "Outrider has no answer for that"
        else -> "Done"
    }
}
