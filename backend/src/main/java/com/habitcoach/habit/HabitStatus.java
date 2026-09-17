package com.habitcoach.habit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The reference implementation stores/returns lowercase strings
 * ("active", "completed", "stopped") — see legacy-reference/backend/db.py
 * and the mobile app's src/types.ts Habit.status union. @JsonValue/
 * @JsonCreator keep the Java enum idiomatic (ACTIVE) while the wire format
 * stays lowercase, matching mobile exactly.
 */
public enum HabitStatus {
    ACTIVE,
    COMPLETED,
    STOPPED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static HabitStatus fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
