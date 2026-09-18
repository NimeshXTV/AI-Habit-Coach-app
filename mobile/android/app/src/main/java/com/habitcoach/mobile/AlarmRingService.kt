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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Keeps the habit alarm ringing — alternating beep -> spoken motivation ->
 * beep -> ... — until the user explicitly acts (DONE / SNOOZE / MISSED /
 * STOP ALARM). A foreground Service, not a JS timer, is the only mechanism
 * that reliably survives the app being backgrounded, swiped away from
 * Recents, or the phone being locked: Android does not throttle or kill an
 * active foreground service the way it throttles JS execution or a
 * BroadcastReceiver's short-lived onReceive(). `android:stopWithTask` is
 * explicitly `false` in the manifest for exactly this reason — removing
 * the app from Recents must NOT silence the ring.
 *
 * The spoken line is synthesized HERE, natively, via Android's own
 * TextToSpeech engine — not by mobile/src/audioLifecycle.ts's (now-removed)
 * JS-side speech call. That JS path only ever ran if MainActivity actually
 * reached the foreground and the JS bridge was alive to run the
 * openCoachSignal effect; in practice `startActivity()`'s background-launch
 * exemption did not reliably bring the Activity up in testing, silently
 * dropping the voice half of the alarm while the beep (fully native,
 * unaffected) kept working. Speaking a short, always-available line
 * natively — instead of the richer AI-generated text, which requires a
 * network round trip this Service cannot block on when it must start
 * ringing within milliseconds of firing — trades personalization for the
 * one property that actually matters here: it is guaranteed to happen.
 *
 * Started by AlarmReceiver the INSTANT the alarm fires — native-to-native,
 * with no dependency on MainActivity/JS having loaded yet. Stopped by
 * AlarmModule.stopRinging() (JS's audioLifecycle.stopAlarmSequence(), the
 * single choke point already reached by every one of Done /
 * Snooze-confirm / open-Missed-picker / STOP ALARM) or by
 * AlarmActionReceiver's own Snooze/Stop handling — either way tears down
 * both the beep and any in-flight speech together.
 *
 * This notification's channel deliberately has NO sound of its own — the
 * MediaPlayer/TextToSpeech cycle below is the alarm's actual audio. Giving
 * the channel a sound too would layer a second one-shot chime underneath
 * every time the notification is (re)posted.
 */
class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var cycleRunning = false
    private var spokenLine = "Time to go."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            Log.d(TAG, "TTS init status=$status")
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "speech onDone")
                        // Runs on a TTS-internal thread — hop back to main
                        // before touching MediaPlayer/Handler state.
                        handler.post { onSpeechFinished() }
                    }
                    @Deprecated("required override of the abstract legacy signature")
                    override fun onError(utteranceId: String?) {
                        handler.post { onSpeechFinished() }
                    }
                })
                ttsReady = true
                Log.d(TAG, "TTS ready")
            }
            // status != SUCCESS: no crash, no fallback text-to-speech —
            // the beep alone (already ringing, see onStartCommand) still
            // satisfies the hard requirement that the alarm itself works.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val habitId = intent?.getIntExtra(AlarmReceiver.EXTRA_HABIT_ID, -1) ?: -1
        val kind = intent?.getStringExtra(AlarmReceiver.EXTRA_KIND) ?: AlarmReceiver.KIND_DAILY
        val habitName = intent?.getStringExtra(AlarmReceiver.EXTRA_HABIT_NAME) ?: "Habit"
        val habitEmoji = intent?.getStringExtra(AlarmReceiver.EXTRA_HABIT_EMOJI) ?: "🎯"
        val timeOfDay = intent?.getStringExtra(AlarmReceiver.EXTRA_TIME_OF_DAY)
        spokenLine = "Time for $habitName. You can do this!"

        val notification = buildNotification(habitId, kind, habitName, habitEmoji, timeOfDay)
        // startForeground() MUST be called within 5s of startForegroundService()
        // (see AlarmRingService.start()) or the system kills the process —
        // this happens first, before touching MediaPlayer/TTS at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!cycleRunning) {
            cycleRunning = true
            startBeepPhase()
        }
        return START_NOT_STICKY
    }

    /** Beeps for BEEP_DURATION_MS, then hands off to the spoken phase — see
     * onSpeechFinished() for the return trip. Runs indefinitely until
     * stopSelf()/onDestroy() (Stop/Snooze/Done/Missed all route here). */
    private fun startBeepPhase() {
        Log.d(TAG, "beep phase start")
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
            // Playback failure must never crash the process — degraded,
            // not fatal. Still attempt to move on to the speech phase so
            // the cycle doesn't stall entirely.
        }
        handler.postDelayed({ startSpeechPhase() }, BEEP_DURATION_MS)
    }

    private fun startSpeechPhase() {
        Log.d(TAG, "speech phase start, ttsReady=$ttsReady, line=\"$spokenLine\"")
        if (!cycleRunning) return
        releasePlayer() // beep stops; only one audio source plays at a time
        if (ttsReady) {
            val params = android.os.Bundle()
            tts?.speak(spokenLine, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            // onSpeechFinished() (below) continues the cycle once this
            // utterance completes — see the UtteranceProgressListener above.
        } else {
            // TTS engine not ready yet (rare — init is normally near-
            // instant) — skip straight back to beeping rather than stall
            // silently until it might become ready later.
            onSpeechFinished()
        }
    }

    private fun onSpeechFinished() {
        if (!cycleRunning) return
        startBeepPhase()
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
        cycleRunning = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    /**
     * Reproduces the native Android Clock alarm notification's shape:
     * a heads-up, non-dismissible ("Alarm" category + ongoing) card with
     * the fire time and "Alarm" in the title, the habit as the body, and
     * two direct action buttons (Snooze / Stop) that work via
     * AlarmActionReceiver — entirely native, no JS/app-open required (see
     * that receiver's javadoc). Tapping the body itself (not an action
     * button) still opens the app via contentIntent, same as before.
     */
    private fun buildNotification(habitId: Int, kind: String, habitName: String, habitEmoji: String, timeOfDay: String?): Notification {
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

        val timeLabel = SimpleDateFormat("h:mm a", Locale.US).format(System.currentTimeMillis())
        val title = "$timeLabel  Alarm"
        val body = "$habitEmoji $habitName"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(actionButton(habitId, habitName, habitEmoji, timeOfDay, AlarmActionReceiver.ACTION_SNOOZE, "Snooze"))
            .addAction(actionButton(habitId, habitName, habitEmoji, timeOfDay, AlarmActionReceiver.ACTION_STOP, "Stop"))
            .build()
    }

    private fun actionButton(
        habitId: Int,
        habitName: String,
        habitEmoji: String,
        timeOfDay: String?,
        action: String,
        label: String
    ): NotificationCompat.Action {
        val intent = Intent(this, AlarmActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
            putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
            if (timeOfDay != null) putExtra(AlarmReceiver.EXTRA_TIME_OF_DAY, timeOfDay)
        }
        // Distinct request code per (habitId, action) — otherwise Snooze and
        // Stop for the same habit would collide and overwrite each other's
        // PendingIntent.
        val requestCode = "$action-$habitId".hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(applicationInfo.icon, label, pendingIntent).build()
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
        private const val BEEP_DURATION_MS = 4000L
        private const val UTTERANCE_ID = "habit_alarm_motivation"
        private const val TAG = "AlarmRingService"

        fun start(context: Context, habitId: Int, kind: String, habitName: String, habitEmoji: String, timeOfDay: String? = null) {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(AlarmReceiver.EXTRA_KIND, kind)
                putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
                putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
                if (timeOfDay != null) putExtra(AlarmReceiver.EXTRA_TIME_OF_DAY, timeOfDay)
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
