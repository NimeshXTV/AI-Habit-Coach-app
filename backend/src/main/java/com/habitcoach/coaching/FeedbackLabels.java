package com.habitcoach.coaching;

import java.util.Map;

/** Ported verbatim from legacy-reference/backend/intervention_engine.py's
 * FEEDBACK_LABELS dict. */
public final class FeedbackLabels {

    public static final Map<String, String> LABELS = Map.of(
            "too_tired", "too tired",
            "no_time", "didn't have enough time",
            "forgot", "forgot",
            "something_came_up", "something came up",
            "not_feeling_it", "didn't feel like doing it",
            "other", "something else"
    );

    private FeedbackLabels() {
    }

    public static String labelOrDefault(String reason, String defaultLabel) {
        String label = reason == null ? null : LABELS.get(reason);
        return label != null ? label : defaultLabel;
    }
}
