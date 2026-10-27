package com.songnotes.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps microphone capture alive while the app is backgrounded (screen lock,
 * switching apps mid-take) and shows that a take is rolling: a mic icon in
 * the status bar with a running timer, like Samsung Voice Recorder. It owns
 * no audio state itself — [MainActivity]'s `AudioEngine` keeps recording
 * exactly as it would in the foreground.
 *
 * On Android 16+ the notification asks to be promoted (Live Updates), which
 * on One UI 8 puts the timer chip in the status bar and the Now Bar. That's
 * a request: the system decides, and older versions show the ordinary
 * ongoing notification. Nothing shows at all on Android 13+ unless the user
 * granted notification permission (asked once, on the first Record);
 * recording works either way.
 *
 * Known limitation: swiping the app away from Recents kills this service
 * with the process, and a take in progress is lost (already-written bytes
 * survive). Surviving that would mean the service owning the engine.
 */
class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the Scratchpad is recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val songTitle = intent?.getStringExtra(EXTRA_SONG_TITLE)?.takeIf { it.isNotBlank() }
        // Same as tapping the launcher icon: brings the running app (and its
        // engine) back rather than starting a second activity.
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Recording")
            .setContentText(songTitle?.let { "Scratchpad · $it" } ?: "Scratchpad")
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
        if (Build.VERSION.SDK_INT >= 36) {
            // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36); core-ktx
            // doesn't wrap it yet.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        }

        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_SONG_TITLE = "song_title"

        fun start(context: Context, songTitle: String) {
            context.startForegroundService(
                Intent(context, RecordingForegroundService::class.java).putExtra(EXTRA_SONG_TITLE, songTitle),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
