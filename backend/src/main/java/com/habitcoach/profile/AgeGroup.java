package com.habitcoach.profile;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Deterministic age bucketing so the AI-facing facts payload can carry a
 * coarse "AGE_GROUP" hint (see CoachingService) without the backend ever
 * writing age-specific coaching TEXT itself — the wording stays entirely
 * Strands' job (see agent.py's SYSTEM_PROMPT). Boundaries are simple,
 * ordinary life-stage buckets, not a clinical classification.
 */
public enum AgeGroup {
    CHILD,
    TEEN,
    ADULT,
    OLDER_ADULT;

    public static AgeGroup of(int age) {
        if (age < 13) return CHILD;
        if (age < 18) return TEEN;
        if (age < 60) return ADULT;
        return OLDER_ADULT;
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
