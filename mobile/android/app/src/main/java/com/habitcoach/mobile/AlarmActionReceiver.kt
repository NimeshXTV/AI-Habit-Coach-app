package com.habitcoach.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the two action buttons on the alarm's heads-up notification
 * (see AlarmRingService.buildNotification) — Stop and Snooze. Both run
 * with NO JS/React context required: this is the "must not depend on RN
 * JS being alive" path called for by the offline-first architecture. Any
 * state that needs to survive until JS next runs is appended to
 * NativeEventLog, which mobile/src/localStore.ts drains on next app
 * foreground.
 */
class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getIntExtra(AlarmReceiver.EXTRA_HABIT_ID, -1)
        if (habitId < 0) return
        val habitName = intent.getStringExtra(AlarmReceiver.EXTRA_HABIT_NAME) ?: "Habit"
        val habitEmoji = intent.getStringExtra(AlarmReceiver.EXTRA_HABIT_EMOJI) ?: "🎯"
        val timeOfDay = intent.getStringExtra(AlarmReceiver.EXTRA_TIME_OF_DAY)

        when (intent.action) {
            ACTION_STOP -> handleStop(context, habitId)
            ACTION_SNOOZE -> handleSnooze(context, habitId, habitName, habitEmoji, timeOfDay)
        }
    }

    /** Exactly matches STOP ALARM's existing in-app semantics (see
     * audioLifecycle.ts/HabitScreen.tsx's stopAlarm()): silence only, no
     * state change, permanent schedule untouched. */
    private fun handleStop(context: Context, habitId: Int) {
        AlarmRingService.stop(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(AlarmModule.requestCodeFor(habitId, AlarmReceiver.KIND_DAILY))
        notificationManager.cancel(AlarmModule.requestCodeFor(habitId, AlarmReceiver.KIND_SNOOZE))
    }

    /**
     * Mirrors HabitScreen.tsx's handleSnoozeChoice() 3-strikes rule, but
     * entirely natively: the shared per-(habitId, calendar day) counter
     * (NativeEventLog, kept in sync with JS via AlarmModule.syncSnoozeCount
     * so it's correct regardless of which UI actually recorded each prior
     * snooze) decides whether this is a real snooze or the terminal 3rd
     * one. A default 5-minute duration is used, matching the plain
     * single-tap "Snooze" action Android's own Clock notification offers —
     * the in-app 5/10/15 picker (opened by tapping the notification body
     * instead of this action) remains the way to choose a longer duration.
     */
    private fun handleSnooze(context: Context, habitId: Int, habitName: String, habitEmoji: String, timeOfDay: String?) {
        AlarmRingService.stop(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(AlarmModule.requestCodeFor(habitId, AlarmReceiver.KIND_DAILY))
        notificationManager.cancel(AlarmModule.requestCodeFor(habitId, AlarmReceiver.KIND_SNOOZE))

        val count = NativeEventLog.incrementSnoozeCountToday(context, habitId)
        if (count >= MAX_SNOOZES_PER_DAY) {
            NativeEventLog.appendEvent(context, "missed", habitId)
            // Belt-and-suspenders, same reasoning as AlarmReceiver's own
            // daily self-chain: tomorrow's alarm must exist even if the
            // user never opens the app to see today marked missed.
            if (timeOfDay != null) {
                AlarmModule.scheduleNextDaily(context, habitId, habitName, habitEmoji, timeOfDay)
            }
            return
        }

        NativeEventLog.appendEvent(context, "snoozed", habitId)
        AlarmModule.schedule(
            context,
            habitId,
            AlarmReceiver.KIND_SNOOZE,
            System.currentTimeMillis() + DEFAULT_SNOOZE_MINUTES * 60_000L,
            habitName,
            habitEmoji,
            timeOfDay ?: ""
        )
    }

    companion object {
        const val ACTION_STOP = "com.habitcoach.mobile.ALARM_ACTION_STOP"
        const val ACTION_SNOOZE = "com.habitcoach.mobile.ALARM_ACTION_SNOOZE"
        private const val DEFAULT_SNOOZE_MINUTES = 5
        private const val MAX_SNOOZES_PER_DAY = 3
    }
}
