package com.habitcoach.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * Fires when a habit alarm's scheduled time is reached — invoked by
 * AlarmManager (see AlarmModule.scheduleAlarm, which uses setAlarmClock()).
 *
 * setAlarmClock() is the one AlarmManager API whose broadcast receiver is
 * explicitly exempted from Android's background-activity-launch
 * restrictions (see Android's documented BAL exceptions) — that exemption
 * is what lets startActivity() below actually put MainActivity on screen
 * immediately, even while the app is fully backgrounded or the phone is
 * locked, with no tap required. This is the same mechanism the built-in
 * Clock app uses; a plain expo-notifications scheduled notification has no
 * such privilege, which is why it could only ever open the app on tap.
 *
 * The actual continuous ringing + its persistent notification are owned by
 * AlarmRingService (a foreground Service), not this receiver — a
 * BroadcastReceiver's onReceive() only runs for a few seconds and cannot
 * itself keep anything looping. AlarmRingService.start() is a fire-and-forget
 * native-to-native call: the ring begins immediately regardless of whether
 * MainActivity/JS ever loads, and keeps going after this onReceive() returns.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getIntExtra(EXTRA_HABIT_ID, -1)
        if (habitId < 0) return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_DAILY
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"
        val habitEmoji = intent.getStringExtra(EXTRA_HABIT_EMOJI) ?: "🎯"
        val timeOfDay = intent.getStringExtra(EXTRA_TIME_OF_DAY)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "habitcoach:AlarmWakeLock"
        )
        wakeLock.acquire(15_000L)
        try {
            AlarmRingService.start(context, habitId, kind, habitName, habitEmoji)
            launchAlarmActivity(context, habitId, kind, habitName, habitEmoji)

            // The daily alarm is a one-shot trigger for "today's occurrence" —
            // immediately chain-schedule tomorrow's so the permanent daily
            // alarm keeps going even if the user never opens the app to act
            // on today's (STOP ALARM, or just ignoring it, must not break
            // tomorrow's alarm).
            if (kind == KIND_DAILY && timeOfDay != null) {
                AlarmModule.scheduleNextDaily(context, habitId, habitName, habitEmoji, timeOfDay)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun launchAlarmActivity(context: Context, habitId: Int, kind: String, habitName: String, habitEmoji: String) {
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_HABIT_ID, habitId)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_HABIT_NAME, habitName)
            putExtra(EXTRA_HABIT_EMOJI, habitEmoji)
        }
        context.startActivity(activityIntent)
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_HABIT_EMOJI = "habitEmoji"
        const val EXTRA_TIME_OF_DAY = "timeOfDay"
        const val KIND_DAILY = "daily"
        const val KIND_SNOOZE = "snooze"
    }
}
