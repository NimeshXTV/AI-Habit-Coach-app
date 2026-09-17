package com.habitcoach.profile;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Onboarding asks for Male / Female / Others — OTHER is the backend/wire
 * value for "Others". Lowercase on the wire, same @JsonValue/@JsonCreator
 * pattern as HabitStatus/DayStatus, so mobile speaks plain lowercase
 * strings ("male" | "female" | "other").
 */
public enum Gender {
    MALE,
    FEMALE,
    OTHER;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Gender fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
