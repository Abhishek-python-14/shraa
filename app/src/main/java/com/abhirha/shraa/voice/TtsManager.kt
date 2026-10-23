package com.abhirha.shraa.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.abhirha.shraa.data.SettingsStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android Text-to-Speech with selectable voice, speed and pitch. Callbacks arrive on the main thread. */
class TtsManager(context: Context, private val settings: SettingsStore) {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = complete(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = complete(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.setOnUtteranceProgressListener(progressListener)
        }
    }

    private fun complete(id: String?) {
        val cb = id?.let { callbacks.remove(it) } ?: return
        main.post { cb() }
    }

    fun availableVoices(): List<Voice> =
        tts?.voices.orEmpty()
            .filter { it.locale.language == "hi" || it.locale.language == "en" }
            .sortedBy { it.name }
            .take(MAX_VOICES)

    /** Speaks [text] and calls [onDone] when finished (or immediately if speech is not possible). */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val engine = tts
        if (!ready || engine == null || text.isBlank()) {
            onDone()
            return
        }
        applySettings(engine, text)
        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone()
            return
        }
        // Safety net: never get stuck if a completion callback is lost.
        main.postDelayed({ callbacks.remove(id)?.invoke() }, SPEAK_TIMEOUT_MS)
    }

    private fun applySettings(engine: TextToSpeech, text: String) {
        engine.setSpeechRate(settings.ttsRate)
        engine.setPitch(settings.ttsPitch)
        val chosen = settings.ttsVoice?.let { name -> engine.voices?.firstOrNull { it.name == name } }
        if (chosen != null) {
            engine.setVoice(chosen)
        } else {
            val hindi = text.any { it in '\u0900'..'\u097F' }
            engine.setLanguage(if (hindi) Locale("hi", "IN") else Locale("en", "IN"))
        }
    }

    private companion object {
        const val MAX_VOICES = 40
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
