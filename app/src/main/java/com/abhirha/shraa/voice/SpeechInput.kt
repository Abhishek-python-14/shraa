package com.abhirha.shraa.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.abhirha.shraa.data.SettingsStore

/** Thin wrapper over Android's SpeechRecognizer (which must run on the main thread; handled here). */
class SpeechInput(context: Context, private val settings: SettingsStore) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** N-best alternatives and confidences of the last final result (used for wake-word checking). */
    @Volatile var lastAlternatives: List<String> = emptyList()
        private set
    @Volatile var lastConfidences: FloatArray? = null
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(
        onPartial: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onFail: (Int) -> Unit,
        preferOffline: Boolean = false,
        silenceMs: Long? = null
    ) {
        main.post {
            release()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer === r) firstResult(partialResults)?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    if (recognizer !== r) return // stale session
                    lastAlternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    lastConfidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    onFinalResult(firstResult(results).orEmpty())
                }

                override fun onError(error: Int) {
                    if (recognizer === r) onFail(error)
                }
            })
            r.startListening(recognizerIntent(preferOffline, silenceMs))
        }
    }

    fun stop() {
        main.post { release() }
    }

    private fun recognizerIntent(preferOffline: Boolean, silenceMs: Long?) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.speechLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (silenceMs != null) {
                // Finish quickly after a short phrase such as "Shraa" (some recognizers ignore this).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            }
        }

    private fun release() {
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
