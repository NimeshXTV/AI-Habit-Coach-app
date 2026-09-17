package com.habitcoach.coaching;

/** source is "strands" or "template" (fallback) — mirrors the reference's
 * generate_intervention() return shape. */
public record InterventionResult(String text, InterventionStrategy strategy, String source) {
}
