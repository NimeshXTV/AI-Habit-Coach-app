package com.habitcoach.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The ONLY piece of habit state written directly by native code (from
 * AlarmActionReceiver, which runs with no JS/React context alive — see its
 * javadoc). Deliberately NOT the same store the JS side owns
 * (mobile/src/localStore.ts, AsyncStorage-backed): two separate SQLite-ish
 * engines (AsyncStorage's own DB and a hand-opened file) sharing one file
 * from two runtimes is a real concurrency/locking risk for little benefit.
 * Instead this is a tiny, exclusively-native SharedPreferences log that JS
 * drains on every app foreground/load (see AlarmModule.drainNativeEvents,
 * called from localStore.ts) and folds into its own richer store — a
 * queue handoff, not shared storage.
 *
 * Snooze counting is date-scoped (a fresh count each calendar day) and
 * mirrors JS's own count via `syncSnoozeCount` (called after every
 * successful snooze recording, wherever it happened) so the "3rd snooze"
 * decision is correct regardless of whether earlier snoozes for today came
 * from the native notification action or the in-app picker.
 */
object NativeEventLog {
    private const val PREFS_NAME = "habit_coach_native_events"
    private const val KEY_PENDING_EVENTS = "pending_events"
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayKey(habitId: Int): String = "snooze_${habitId}_${DATE_FORMAT.format(System.currentTimeMillis())}"

    fun getSnoozeCountToday(context: Context, habitId: Int): Int =
        prefs(context).getInt(todayKey(habitId), 0)

    /** Returns the count AFTER incrementing. */
    fun incrementSnoozeCountToday(context: Context, habitId: Int): Int {
        val next = getSnoozeCountToday(context, habitId) + 1
        prefs(context).edit().putInt(todayKey(habitId), next).apply()
        return next
    }

    /** Called from JS (via AlarmModule) after it records a snooze that
     * happened through the in-app picker, so native stays in sync with
     * whatever JS computed as the authoritative count for today. */
    fun setSnoozeCountToday(context: Context, habitId: Int, count: Int) {
        prefs(context).edit().putInt(todayKey(habitId), count).apply()
    }

    /** Appends a `{type, habitId, at}` event for JS to apply next time it
     * runs. `type` is "snoozed" or "missed" — mirrors the same action
     * vocabulary JS/backend already use (see types.ts). */
    fun appendEvent(context: Context, type: String, habitId: Int) {
        val sp = prefs(context)
        val existing = JSONArray(sp.getString(KEY_PENDING_EVENTS, "[]"))
        val event = JSONObject().apply {
            put("type", type)
            put("habitId", habitId)
            put("at", System.currentTimeMillis())
        }
        existing.put(event)
        sp.edit().putString(KEY_PENDING_EVENTS, existing.toString()).apply()
    }

    /** Returns the queued events as a JSON array string and atomically
     * clears it — each event is handed to JS exactly once. */
    fun drainEvents(context: Context): String {
        val sp = prefs(context)
        val existing = sp.getString(KEY_PENDING_EVENTS, "[]") ?: "[]"
        sp.edit().remove(KEY_PENDING_EVENTS).apply()
        return existing
    }
}
