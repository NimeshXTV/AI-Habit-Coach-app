package com.habitcoach.goal;

import jakarta.validation.constraints.NotNull;

/**
 * Mirrors the reference's GoalIn: `text` must be present (matching
 * pydantic's required-field behavior) but an empty string is allowed —
 * goal_parser.py's fallbacks already handle that ("My Habit", default
 * time/duration/days). @NotNull rather than @NotBlank is deliberate: it
 * rejects a missing/null field but not "".
 */
public record GoalRequest(@NotNull String text) {
}
