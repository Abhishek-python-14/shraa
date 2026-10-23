package com.abhirha.shraa.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Hides the recognizer's "bip" while SHRAA only waits for "Shraa suno". The beep is played when a
 * listening session starts, which in idle mode happens every few seconds. Right before each idle session
 * the streams the beep can use are muted for a moment and then restored. Only streams this class muted
 * itself are restored (a stream the owner already muted is left alone), and the ringer is never touched,
 * so calls still ring. The beep of the real command listening (after "Shraa suno") stays audible:
 * it is the sign that SHRAA is listening.
 */
class QuietEarcon(context: Context, private val enabled: () -> Boolean) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.applicationContext.getSharedPreferences("shraa_quiet", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val muted = LinkedHashSet<Int>()
    private val restore = Runnable { unmuteNow() }

    /** Mute the beep for [ms]. Music that is playing is never muted. */
    fun muteBriefly(ms: Long = 1800L) {
        if (!enabled()) return
        main.removeCallbacks(restore)
        val streams = mutableListOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
        if (!am.isMusicActive) streams += AudioManager.STREAM_MUSIC
        for (s in streams) {
            if (s in muted) continue
            try {
                if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(s)) continue
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                muted += s
            } catch (_: SecurityException) {
                // Do Not Disturb rules forbid touching this stream: skip it.
            } catch (_: RuntimeException) {
            }
        }
        save()
        main.postDelayed(restore, ms)
    }

    fun unmuteNow() {
        main.removeCallbacks(restore)
        for (s in muted.toList()) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: RuntimeException) {}
        }
        muted.clear()
        save()
    }

    /** If the app was killed while muted, give those streams back. */
    fun recover() {
        val saved = prefs.getStringSet("muted", emptySet()).orEmpty()
        for (s in saved) {
            try { am.adjustStreamVolume(s.toInt(), AudioManager.ADJUST_UNMUTE, 0) } catch (_: RuntimeException) {}
        }
        prefs.edit().putStringSet("muted", emptySet()).apply()
    }

    private fun save() {
        prefs.edit().putStringSet("muted", muted.map { it.toString() }.toSet()).apply()
    }
}
