package com.habitcoach.coaching;

/**
 * Ported from legacy-reference/backend/intervention_engine.py's _fmt12()
 * and suggest_alternate_time(). Text-only formatting — the actual stored/
 * scheduled time_of_day always stays 24h HH:MM; only coaching text uses
 * 12-hour format.
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String to12Hour(String timeOfDay) {
        String[] parts = timeOfDay.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        String period = hour >= 12 ? "PM" : "AM";
        int hour12 = hour % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }
        return minute == 0 ? String.format("%d %s", hour12, period) : String.format("%d:%02d %s", hour12, minute, period);
    }

    /** A concrete one-hour-later alternative offered alongside a reschedule
     * suggestion (e.g. 18:00 -> 19:00). The user always gets a "choose
     * another time" option too — this is just the default suggestion. */
    public static String suggestAlternateTime(String timeOfDay) {
        String[] parts = timeOfDay.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        hour = (hour + 1) % 24;
        return String.format("%02d:%02d", hour, minute);
    }
}
