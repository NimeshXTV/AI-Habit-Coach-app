package com.habitcoach.journey;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * See HabitStatus's javadoc — same reasoning: the reference implementation
 * and the mobile app's src/types.ts HabitDay.status union are lowercase
 * ("pending", "done", "missed", "snoozed").
 */
public enum DayStatus {
    PENDING,
    DONE,
    MISSED,
    SNOOZED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static DayStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
