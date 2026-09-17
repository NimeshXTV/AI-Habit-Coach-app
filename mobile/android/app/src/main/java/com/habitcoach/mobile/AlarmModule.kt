package com.habitcoach.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import java.util.Calendar

/**
 * JS-facing bridge for the native alarm path (see AlarmReceiver's javadoc
 * for why this exists instead of relying on expo-notifications alone).
 * Every method here is keyed by (habitId, kind) exactly like the old
 * expo-notifications identifiers (`daily-{habitId}` / `snooze-{habitId}`)
 * were — habit-scoped, so operations on one habit's alarm can never touch
 * another's.
 */
class AlarmModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "AlarmModule"

    /** Schedules a one-shot alarm at [triggerAtMillis] via
     * AlarmManager.setAlarmClock() — see AlarmReceiver for why this
     * specific API is required for reliable background/locked delivery. */
    @ReactMethod
    fun scheduleAlarm(
        habitId: Double,
        kind: String,
        triggerAtMillis: Double,
        habitName: String,
        habitEmoji: String,
        timeOfDay: String,
        promise: Promise
    ) {
        try {
            schedule(reactApplicationContext, habitId.toInt(), kind, triggerAtMillis.toLong(), habitName, habitEmoji, timeOfDay)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("schedule_alarm_error", e)
        }
    }

    @ReactMethod
    fun cancelAlarm(habitId: Double, kind: String, promise: Promise) {
        try {
            cancel(reactApplicationContext, habitId.toInt(), kind)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("cancel_alarm_error", e)
        }
    }

    /** Dismisses a currently-shown alarm notification for this habit/kind —
     * used by STOP ALARM alongside the JS-side expo-notifications dismissal.
     * Legacy/best-effort: AlarmRingService's own notification (the one that
     * actually matters while an alarm is ringing) is torn down by
     * stopRinging() below, not by this — see that method's javadoc. */
    @ReactMethod
    fun dismissAlarmNotification(habitId: Double, kind: String, promise: Promise) {
        try {
            val notificationManager = reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            notificationManager.cancel(requestCodeFor(habitId.toInt(), kind))
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("dismiss_alarm_error", e)
        }
    }

    /** Stops the continuously-ringing native alarm (see AlarmRingService) —
     * called from JS's audioLifecycle.stopAlarmSequence(), the single choke
     * point already reached by Done / Snooze-confirm / opening the Missed
     * picker / STOP ALARM, so every one of those stops the ring identically.
     * Stopping the service also tears down its foreground notification
     * (standard Android behavior), so nothing else needs to clean that up. */
    @ReactMethod
    fun stopRinging(promise: Promise) {
        try {
            AlarmRingService.stop(reactApplicationContext)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("stop_ringing_error", e)
        }
    }

    /** Cold-start pull: what habit/kind (if any) launched MainActivity this
     * time. Consumed once — calling this again returns null until another
     * alarm launches the app. Mirrors expo-notifications'
     * getLastNotificationResponseAsync() pull-based pattern for parity. */
    @ReactMethod
    fun getInitialAlarm(promise: Promise) {
        val activity = reactApplicationContext.currentActivity as? MainActivity
        val extras = activity?.consumeInitialAlarmExtras()
        if (extras == null) {
            promise.resolve(null)
            return
        }
        val map: WritableMap = Arguments.createMap()
        map.putInt("habitId", extras.habitId)
        map.putString("kind", extras.kind)
        promise.resolve(map)
    }

    companion object {
        private const val ACTION_PREFIX = "com.habitcoach.mobile.ALARM_"

        fun requestCodeFor(habitId: Int, kind: String): Int = "$kind-$habitId".hashCode()

        private fun buildPendingIntent(context: Context, habitId: Int, kind: String, habitName: String, habitEmoji: String, timeOfDay: String?): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_PREFIX + requestCodeFor(habitId, kind)
                putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(AlarmReceiver.EXTRA_KIND, kind)
                putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
                putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
                if (timeOfDay != null) putExtra(AlarmReceiver.EXTRA_TIME_OF_DAY, timeOfDay)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCodeFor(habitId, kind),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedule(context: Context, habitId: Int, kind: String, triggerAtMillis: Long, habitName: String, habitEmoji: String, timeOfDay: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operationPendingIntent = buildPendingIntent(context, habitId, kind, habitName, habitEmoji, timeOfDay)

            val showIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmReceiver.EXTRA_HABIT_ID, habitId)
                putExtra(AlarmReceiver.EXTRA_KIND, kind)
                putExtra(AlarmReceiver.EXTRA_HABIT_NAME, habitName)
                putExtra(AlarmReceiver.EXTRA_HABIT_EMOJI, habitEmoji)
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                requestCodeFor(habitId, kind),
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
            // setAlarmClock (not setExactAndAllowWhileIdle) is deliberate: it's
            // exempt from Doze/App-Standby alarm throttling AND its receiver is
            // exempt from background-activity-launch restrictions — no
            // SCHEDULE_EXACT_ALARM grant is even required for it.
            alarmManager.setAlarmClock(info, operationPendingIntent)
        }

        fun cancel(context: Context, habitId: Int, kind: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context, habitId, kind, "", "", null)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        /** Called by AlarmReceiver right after a "daily" alarm fires, to
         * chain-schedule tomorrow's occurrence at the same time — see
         * AlarmReceiver's javadoc on why this can't wait for a JS action. */
        fun scheduleNextDaily(context: Context, habitId: Int, habitName: String, habitEmoji: String, timeOfDay: String) {
            val parts = timeOfDay.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return
            val next = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            schedule(context, habitId, AlarmReceiver.KIND_DAILY, next.timeInMillis, habitName, habitEmoji, timeOfDay)
        }
    }
}

data class AlarmExtras(val habitId: Int, val kind: String)
