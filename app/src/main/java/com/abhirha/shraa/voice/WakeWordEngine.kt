package com.abhirha.shraa.voice

import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer

/**
 * Wake-word layer: runs short speech-recognition sessions back to back and fires [onWake] only when
 * an utterance STARTS with "Shraa". Everything else is discarded, never stored, and nothing goes to
 * the AI until the wake word was heard. Sessions prefer on-device recognition, so idle listening
 * stays on the phone whenever an offline speech pack is installed.
 *
 * [onWake] returns true if the assistant took over (the engine stays paused until [resume]),
 * or false if it could not (the engine simply keeps listening).
 */
class WakeWordEngine(
    private val speech: SpeechInput,
    private val looseMode: () -> Boolean = { false },
    /** Listening can never work again (e.g. the microphone permission was taken away). */
    private val onFatal: () -> Unit = {},
    /** A readable problem with idle listening, or null once listening works again. */
    private val onStatus: (String?) -> Unit = {},
    /** Hides the recognizer's start beep while only waiting for the wake phrase. */
    private val quiet: QuietEarcon? = null,
    private val onWake: (String) -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false
    private var errorStreak = 0
    private var preferOffline = true

    fun start() {
        running = true
        paused = false
        errorStreak = 0
        listenLoop()
    }

    fun stop() {
        quiet?.unmuteNow()
        running = false
        main.removeCallbacksAndMessages(null)
        speech.stop()
    }

    fun pause() {
        quiet?.unmuteNow()
        paused = true
        main.removeCallbacksAndMessages(null)
        speech.stop()
    }

    fun resume() {
        if (!running) return
        paused = false
        errorStreak = 0
        // Wait a little so the assistant's own voice / speaker tail is not heard as the wake word.
        restart(RESUME_DELAY_MS)
    }

    /** Checks the top result and the alternatives; skips results the recognizer itself is unsure about. */
    private fun detectWake(text: String): String? {
        val alternatives = speech.lastAlternatives.ifEmpty { listOf(text) }
        val scores = speech.lastConfidences
        val loose = looseMode()
        for ((i, alt) in alternatives.withIndex()) {
            val confidence = scores?.getOrNull(i) ?: 0f
            // 0 = "not provided". A one-word phrase like "Shraa" often scores low: drop only clear noise.
            if (confidence > 0f && confidence < MIN_CONFIDENCE) continue
            WakeWord.extractCommand(alt, loose)?.let { return it }
        }
        return null
    }

    private fun listenLoop() {
        if (!running || paused) return
        quiet?.muteBriefly()
        speech.listen(
            onPartial = {},
            preferOffline = preferOffline,
            silenceMs = 900,
            onFinalResult = { text ->
                errorStreak = 0
                onStatus(null)
                val command = detectWake(text)
                if (command == null) {
                    restart(150)
                } else {
                    paused = true
                    quiet?.unmuteNow() // the command-listening beep after "Shraa suno" is the cue that she listens
                    if (!onWake(command)) {
                        paused = false // assistant was busy: keep listening
                        restart(500)
                    }
                }
            },
            onFail = ::onSessionError
        )
    }

    private fun onSessionError(code: Int) {
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stop(); onFatal() }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(1500)
            // 12 = language not supported, 13 = language unavailable (offline pack missing):
            // give up on offline mode and use the normal recognizer.
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> { preferOffline = false; restart(300) }
            // No speech / no match is normal while idle: just start the next session.
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { errorStreak = 0; onStatus(null); restart(150) }
            else -> {
                // Network/server/client errors: back off so we do not spin and drain the battery.
                errorStreak++
                if (errorStreak >= 3) {
                    preferOffline = false
                    onStatus(describe(code))
                }
                restart((500L * errorStreak).coerceAtMost(5000L))
            }
        }
    }

    /** What a recognizer error code means, in words the owner can act on. */
    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Internet nahi hai aur offline Hindi speech pack bhi nahi mila (error $code). Phone ki Google voice settings me offline language download karo."
        SpeechRecognizer.ERROR_AUDIO -> "Mic open nahi ho pa raha, shayad koi aur app use kar rahi hai (error $code)."
        SpeechRecognizer.ERROR_SERVER -> "Google speech server ka error (error $code), thodi der me khud theek ho sakta hai."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer baar baar band ho raha hai (error $code). Google app update karo ya phone restart karo."
        else -> "Speech error $code. Background band karke dobara ON karo."
    }

    private fun restart(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listenLoop() }, delayMs)
    }

    private companion object {
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
        const val MIN_CONFIDENCE = 0.15f
        const val RESUME_DELAY_MS = 1200L
    }
}
