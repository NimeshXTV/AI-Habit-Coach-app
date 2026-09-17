package com.habitcoach.habit;

/**
 * The habit-tree gamification model (see CLAUDE_CONTEXT.md's "Habit Tree
 * Gamification" task for the product requirement). Deliberately simple and
 * deterministic — a flat delta per action, clamped to [0, 100]:
 *
 *   DONE    +10  (waters the tree)
 *   MISSED  -10  (lets it dry out a little)
 *   SNOOZED   0  (never touches the tree — only a real done/missed does;
 *                 see ActionService, which only calls apply() for those two)
 *
 * A new habit SEEDs at 50 (TreeStage.GROWING) — a young tree already
 * underway, neither struggling nor thriving. Because the delta is flat and
 * symmetric-ish rather than compounding, repeated misses walk the health
 * down through RECOVERING and into DRY gradually (never in one hit, never
 * "destroying" the tree outright), and it is always mathematically
 * recoverable: no matter how low health gets, the next DONE moves it back
 * up by the same fixed amount. There is no notion of a floor that can't be
 * climbed out of — that's the product requirement ("a missed day must
 * NEVER permanently destroy the tree").
 */
public final class TreeHealth {

    public static final int SEED = 50;
    public static final int DONE_DELTA = 10;
    public static final int MISSED_DELTA = -10;
    public static final int MIN = 0;
    public static final int MAX = 100;

    private TreeHealth() {
    }

    /** action is the same "done" | "snoozed" | "missed" string ActionService
     * already validates — anything other than "done"/"missed" (i.e.
     * "snoozed") leaves health unchanged, by design. */
    public static int apply(int currentHealth, String action) {
        int delta = switch (action) {
            case "done" -> DONE_DELTA;
            case "missed" -> MISSED_DELTA;
            default -> 0;
        };
        return clamp(currentHealth + delta);
    }

    private static int clamp(int health) {
        return Math.max(MIN, Math.min(MAX, health));
    }
}
