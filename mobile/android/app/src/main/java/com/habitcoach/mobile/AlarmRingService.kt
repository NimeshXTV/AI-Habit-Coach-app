package com.habitcoach.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the habit alarm CONTINUOUSLY ringing — looping alarm.wav on the
 * ALARM audio stream — until the user explicitly acts (DONE / SNOOZE /
 * MISSED / STOP ALARM). A foreground Service, not a JS timer, is the only
 * mechanism that reliably survives the app being backgrounded, swiped away
 * from Recents, or the phone being locked: Android does not throttle or
 * kill an active foreground service the way it throttles JS execution or a
 * BroadcastReceiver's short-lived onReceive(). `android:stopWithTask` is
 * explicitly `false` in the manifest for exactly this reason — removing
 * the app from Recents must NOT silence the ring.
 *
 * Started by AlarmReceiver the INSTANT the alarm fires — native-to-native,
 * with no dependency on MainActivity/JS having loaded yet, so the ring
 * begins even on a cold start where the JS bundle takes a moment to come
 * up. Stopped by AlarmModule.stopRinging(), which JS's
 * audioLifecycle.stopAlarmSequence() calls — the single choke point already
 * reached by every one of Done / Snooze-confirm / open-Missed-picker /
 * STOP ALARM (see HabitScreen.tsx), so no separate wiring is needed per
 * action.
 *
 * This notification's channel deliberately has NO sound of its own — the
 * looping MediaPlayer below is the alarm's actual audio. Giving the
 * channel a sound too would layer a second one-shot chime underneath the
 * loop every time the notification is (re)posted.
 */
class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val habitId = intent?.getIntExtra(AlarmReceiver.EXTRA_HABIT_ID, -1) ?: -1
        val kind = intent?.getStringExtra(AlarmReceiver.EXTRA_KIND) ?: AlarmReceiver.KIND_DAILY
        val habitName = intent?.getStringExtra(AlarmReceiver.EXTRA_HABIT_NAME) ?: "Habit"
        val habitEmoji = intent?.getStringExtra(AlarmReceiver.EXTRA_HABIT_EMOJI) ?: "🎯"

        val notification = buildNotification(habitId, kind, habitName, habitEmoji)
        // startForeground() MUST be called within 5s of startForegroundService()
        // (see AlarmRingService.start()) or the system kills the process —
        // this happens first, before touching MediaPlayer at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLooping()
        return START_NOT_STICKY
    }

    private fun startLooping() {
        if (mediaPlayer?.isPlaying == true) return // already ringing — a re-fire just refreshes the notification above
        releasePlayer()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm)?.apply {
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                start()
            }
        } catch (e: Exception) {
            // Playback failure must never crash the process — the native
            // launch/notification already happened; losing sound alone is
            // degraded, not fatal.
        }
    }

    private fun releasePlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player == null) return
        try {
            if (player.isPlaying) player.stop()
            player.release()
        } catch (e: Exception) {
            // already released — safe to ignore
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun buildNotification(habitId: Int, kind: String, habitName: String, habitEmoji: String): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(AlarmReceiver.EXTRA_KIND, kind)
            putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
            putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            AlarmModule.requestCodeFor(habitId, kind),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (kind == AlarmReceiver.KIND_SNOOZE) "🔔 Habit alarm (snoozed)" else "🔔 Habit alarm"
        val body = "$habitEmoji $habitName — ringing"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Habit alarm ringing", NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(null, null) // see class javadoc — MediaPlayer owns the audio, not this channel
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 927341
        private const val CHANNEL_ID = "habit-alarm-ringing"

        fun start(context: Context, habitId: Int, kind: String, habitName: String, habitEmoji: String) {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(AlarmReceiver.EXTRA_KIND, kind)
                putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
                putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stopping the service tears down its foreground notification too
         * (standard Android behavior for a startForeground()-associated
         * notification) — no separate NotificationManager.cancel() needed. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmRingService::class.java))
        }
    }
}
