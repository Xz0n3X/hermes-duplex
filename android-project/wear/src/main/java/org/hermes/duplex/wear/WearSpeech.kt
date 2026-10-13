package org.hermes.duplex.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listening, on the watch itself.
 *
 * The phone never hears anything. The watch recognises speech with the
 * engine already on it and sends the text, which is a few dozen bytes rather
 * than the ~32 KB/s each way that streaming PCM over Bluetooth would cost --
 * on a battery the size of a coin, and over a link that drops when an arm
 * moves the wrong way.
 *
 * The trade is real and worth stating: this is half duplex. You cannot talk
 * over the agent on the watch the way you can on the phone, because nothing
 * is listening while it speaks. Barge-in is a phone feature.
 */
class WearSpeech(private val context: Context) {

    private var recogniser: SpeechRecognizer? = null

    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}

    /**
     * Whether this watch can stream recognition results as you speak.
     *
     * Many cannot. A TicWatch Pro 3 Ultra has no `RecognitionService` at
     * all -- `cmd package query-services -a android.speech.RecognitionService`
     * finds nothing -- so `SpeechRecognizer` is not merely awkward there, it
     * is absent. What every Wear device does have is a system voice-input
     * activity, which `ACTION_RECOGNIZE_SPEECH` resolves to; on that watch it
     * is Gboard's.
     *
     * So there are two paths, and the fallback is the common one.
     */
    val streaming: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * The system's own voice input, for watches with no recognition service.
     *
     * Launched by the Activity, because an Intent needs a result and only an
     * Activity can receive one. No partial results come back from this --
     * there is a screen to look at while speaking instead, which is the
     * platform's answer rather than ours.
     */
    fun voiceInputIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Hermes")
        }

    /** Pull the text out of what that activity returned. */
    fun textFrom(data: Intent?): String =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()

    fun listen() {
        if (!streaming) {
            onError("No speech recogniser on this watch")
            return
        }
        stop()
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recogniser = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rms: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListening(false)

            override fun onError(code: Int) {
                onListening(false)
                // Silence is not a failure worth showing: on a watch the
                // recogniser ends a turn the moment a pause looks long
                // enough, and saying "no match" every time someone thinks
                // mid-sentence would be noise.
                if (code != SpeechRecognizer.ERROR_NO_MATCH &&
                    code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    this@WearSpeech.onError("Could not hear that")
                }
            }

            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onFinal(text)
            }

            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onPartial)
            }

            override fun onEvent(type: Int, params: Bundle?) = Unit
        })

        engine.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        )
    }

    fun stop() {
        recogniser?.run {
            runCatching { stopListening() }
            runCatching { destroy() }
        }
        recogniser = null
        onListening(false)
    }
}
