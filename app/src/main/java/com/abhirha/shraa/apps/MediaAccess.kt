package com.abhirha.shraa.apps

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.core.app.NotificationManagerCompat
import com.abhirha.shraa.service.ShraaNotificationListener

/**
 * Controls whatever is playing in music / video apps through Android's official media sessions.
 * Needs "Notification access" (one switch covers every app). Reads only the media session's
 * title and artist, never notification content.
 */
object MediaAccess {
    const val OK = 0
    const val NO_SESSION = 1
    const val UNKNOWN_COMMAND = 2
    const val NO_ACCESS = 3

    fun isEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun sessions(ctx: Context): List<MediaController>? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, ShraaNotificationListener::class.java))
    } catch (e: SecurityException) {
        null
    }

    private fun pick(list: List<MediaController>, pkg: String?): MediaController? {
        val pool = if (pkg == null) list else list.filter { it.packageName == pkg }
        return pool.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: pool.firstOrNull()
    }

    /** [pkg] = one connected app, or null for whatever is active right now. */
    fun control(ctx: Context, pkg: String?, cmd: String): Int {
        val list = sessions(ctx) ?: return NO_ACCESS
        val c = pick(list, pkg) ?: return NO_SESSION
        val t = c.transportControls
        when (cmd.lowercase().trim()) {
            "play", "resume" -> t.play()
            "pause" -> t.pause()
            "stop" -> t.stop()
            "next", "skip" -> t.skipToNext()
            "previous", "back" -> t.skipToPrevious()
            "forward", "fastforward" -> t.fastForward()
            "rewind" -> t.rewind()
            else -> return UNKNOWN_COMMAND
        }
        return OK
    }

    /** "Title - Artist" of the current media, or null. */
    fun nowPlaying(ctx: Context, pkg: String?): String? {
        val c = pick(sessions(ctx) ?: return null, pkg) ?: return null
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return if (artist.isNullOrBlank()) title else "$title, $artist"
    }
}
