package com.songnotes.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Exists solely to satisfy Android's requirement that microphone capture
 * survive backgrounding (screen lock, switching apps mid-take) without the
 * OS killing mic access after its background grace period. It owns no audio
 * state itself — [MainActivity]'s `AudioEngine` keeps recording exactly as
 * it would in the foreground; this just keeps the process alive and visibly
 * recording while it does.
 *
 * Known Phase 1 limitation: if the user force-swipes the app away from
 * Recents, Android kills this service along with the rest of the process —
 * a take in progress is lost mid-recording (already-written bytes on disk
 * survive; anything still in the ring buffer at that instant doesn't).
 * Surviving that would mean moving engine ownership into the service itself
 * rather than the Activity, which is a bigger change than this phase's
 * "prove duplex record/playback works" scope calls for.
 */
class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SongNotes is recording")
            .setSmallIcon(android.R.drawable.ic_media_play) // placeholder — real icon lands with Phase 8 branding
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}
