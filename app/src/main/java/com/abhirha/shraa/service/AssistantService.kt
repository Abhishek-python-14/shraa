package com.abhirha.shraa.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.abhirha.shraa.AssistantController
import com.abhirha.shraa.MainActivity
import com.abhirha.shraa.voice.QuietEarcon
import com.abhirha.shraa.voice.WakeWordEngine

/**
 * Foreground service (microphone type) that keeps the wake-word loop alive.
 * A persistent notification with a Stop button is visible the whole time the mic is open.
 */
class AssistantService : Service() {
    private var engine: WakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) return shutDown()

        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            return shutDown() // Android refused a microphone foreground service (e.g. restarted in background)
        }
        if (!AssistantController.settings.backgroundEnabled) {
            // The switch was turned OFF (or this is a stale restart): never keep the mic open.
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        if (engine == null) {
            val quiet = QuietEarcon(applicationContext) { AssistantController.settings.quietListening }
            quiet.recover()
            engine = WakeWordEngine(
                AssistantController.speech,
                looseMode = { AssistantController.settings.looseWake },
                onFatal = { shutDown() }, // mic permission lost etc.: switch OFF so the toggle never lies
                onStatus = { AssistantController.bgStatus.value = it.orEmpty() },
                quiet = quiet,
                onWake = { command -> AssistantController.onWake(command) }
            ).also {
                AssistantController.wakeEngine = it
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        AssistantController.wakeEngine = null
        super.onDestroy()
    }

    private fun shutDown(): Int {
        AssistantController.setBackground(false)
        stopSelf()
        return START_NOT_STICKY
    }

    /** Keeps the CPU awake so listening continues with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shraa:wake").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SHRAA Background", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SHRAA is active")
            .setContentText("Say 'Shraa suno'")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.abhirha.shraa.STOP"
        private const val CHANNEL_ID = "shraa_bg"
        private const val NOTIF_ID = 1
    }
}
