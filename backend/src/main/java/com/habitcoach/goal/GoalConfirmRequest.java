package com.habitcoach.goal;

import jakarta.validation.constraints.NotNull;

/** Mirrors the reference's GoalConfirmIn. Serializes/deserializes as
 * {"text": ..., "time_of_day": ...} via the global snake_case strategy. */
public record GoalConfirmRequest(@NotNull String text, @NotNull String timeOfDay) {
}
