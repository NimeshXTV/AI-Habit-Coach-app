package com.habitcoach.coaching;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Ported from legacy-reference/backend/intervention_engine.py's STRATEGIES
 * list. Lowercase on the wire (matches the reference's plain strings, e.g.
 * "reduce_task") — this is also literally the `key` sent to the Strands
 * service's POST /generate (see coach_model.py's TEMPLATES dict, which is
 * keyed by these exact same strings).
 */
public enum InterventionStrategy {
    ENCOURAGEMENT,
    REDUCE_TASK,
    REINFORCEMENT,
    ACCOUNTABILITY,
    RESCHEDULE,
    REFLECTION;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static InterventionStrategy fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
