package com.habitcoach.coaching;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Ported from legacy-reference/backend/intervention_engine.py's
 * generate_action_response() kinds. Post-ACTION coaching reaction —
 * distinct from InterventionStrategy, which is the pre-action nudge.
 * Lowercase on the wire, and this is also literally the `key` sent to the
 * Strands service's POST /generate (matches coach_model.py's TEMPLATES
 * dict keys exactly).
 */
public enum ResponseKind {
    COMPLETION_FIRST,
    COMPLETION_FINAL,
    COMPLETION_RECOVERY,
    COMPLETION_MILESTONE,
    COMPLETION_PLAIN,
    MISS_SINGLE,
    MISS_CONSECUTIVE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ResponseKind fromJson(String value) {
        return valueOf(value.toUpperCase());
    }
}
