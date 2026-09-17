package com.habitcoach.coaching;

/** source is "strands" or "template" (fallback) — mirrors the reference's
 * generate_action_response() return shape. */
public record ActionResponseResult(String text, ResponseKind kind, String source, int consecutiveMissed) {
}
