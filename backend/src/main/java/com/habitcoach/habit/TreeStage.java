package com.habitcoach.habit;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Purely a rendering hint derived from treeHealth (see TreeHealth) — never
 * persisted itself, so it can never drift out of sync with the health value
 * it's computed from. Five equal 20-point bands across the 0-100 range.
 */
public enum TreeStage {
    DRY,
    RECOVERING,
    GROWING,
    HEALTHY,
    THRIVING;

    public static TreeStage of(int health) {
        if (health < 20) return DRY;
        if (health < 40) return RECOVERING;
        if (health < 60) return GROWING;
        if (health < 80) return HEALTHY;
        return THRIVING;
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
