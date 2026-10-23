package com.abhirha.shraa.data

import android.content.Context
import com.abhirha.shraa.ai.AiProvider

/** Small non-secret preferences. API keys live in ApiKeyStore (encrypted). */
class SettingsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("shraa_settings", Context.MODE_PRIVATE)

    /** Background assistant switch. */
    var backgroundEnabled: Boolean
        get() = p.getBoolean("bg", false)
        set(v) { p.edit().putBoolean("bg", v).apply() }

    /** Also wake on mishearings like "Sara". Off by default: it can cause accidental activations. */
    var looseWake: Boolean
        get() = p.getBoolean("loose_wake", false)
        set(v) { p.edit().putBoolean("loose_wake", v).apply() }

    /** After a task keep listening briefly for the next command without "Shraa". Off = one command per wake. */
    var followUp: Boolean
        get() = p.getBoolean("follow_up", false)
        set(v) { p.edit().putBoolean("follow_up", v).apply() }

    var ttsRate: Float
        get() = p.getFloat("rate", 1.0f)
        set(v) { p.edit().putFloat("rate", v).apply() }

    var ttsPitch: Float
        get() = p.getFloat("pitch", 1.0f)
        set(v) { p.edit().putFloat("pitch", v).apply() }

    var ttsVoice: String?
        get() = p.getString("voice", null)
        set(v) { p.edit().putString("voice", v).apply() }

    /** Show the SHRAA pop-up over the lock screen after "Shraa suno" (and wake the screen). */
    var lockScreenPopup: Boolean
        get() = p.getBoolean("lock_screen_popup", true)
        set(v) { p.edit().putBoolean("lock_screen_popup", v).apply() }

    /** Hide the recognizer's beep while only waiting for "Shraa suno". */
    var quietListening: Boolean
        get() = p.getBoolean("quiet_listening", true)
        set(v) { p.edit().putBoolean("quiet_listening", v).apply() }

    /** Speech-recognition language: "hi-IN" (Hindi + Hinglish) or "en-IN". */
    var speechLang: String
        get() = p.getString("speech_lang", "hi-IN") ?: "hi-IN"
        set(v) { p.edit().putString("speech_lang", v).apply() }

    /** Id of the AI provider tried first. The others (with a saved key) are backups. */
    var primaryProviderId: String
        get() = p.getString("primary_ai", AiProvider.GEMINI.id) ?: AiProvider.GEMINI.id
        set(v) { p.edit().putString("primary_ai", v).apply() }

    val primaryProvider: AiProvider
        get() = AiProvider.entries.firstOrNull { it.id == primaryProviderId } ?: AiProvider.GEMINI
}
