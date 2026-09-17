package com.habitcoach.habit;

import jakarta.validation.constraints.NotNull;

/** Mirrors the reference's ScheduleIn: {"time_of_day": "HH:MM"} 24h. */
public record ScheduleRequest(@NotNull String timeOfDay) {
}
