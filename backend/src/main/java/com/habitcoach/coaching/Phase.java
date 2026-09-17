package com.habitcoach.coaching;

/**
 * Ported from legacy-reference/backend/intervention_engine.py's _phase().
 * Internal only — never serialized, matching the reference (phase is not
 * part of any API response, only used to pick a strategy).
 *
 * The ratio thresholds are literal fractions of a 21-day journey (3/21,
 * 7/21, 14/21, 18/21) applied to day_number/total_days — NOT rescaled to
 * total_days. This is a deliberate reference behavior (a 14-day habit still
 * uses these same fixed fractions), preserved exactly rather than
 * "corrected".
 */
public enum Phase {
    GETTING_STARTED,
    BUILDING_CONSISTENCY,
    HANDLING_RESISTANCE,
    REINFORCEMENT,
    COMPLETION;

    public static Phase of(int dayNumber, int totalDays) {
        double ratio = (double) dayNumber / totalDays;
        if (ratio <= 3.0 / 21) {
            return GETTING_STARTED;
        }
        if (ratio <= 7.0 / 21) {
            return BUILDING_CONSISTENCY;
        }
        if (ratio <= 14.0 / 21) {
            return HANDLING_RESISTANCE;
        }
        if (ratio <= 18.0 / 21) {
            return REINFORCEMENT;
        }
        return COMPLETION;
    }
}
