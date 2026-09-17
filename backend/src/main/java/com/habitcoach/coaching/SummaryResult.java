package com.habitcoach.coaching;

/** source is "strands" or "template" (fallback) — mirrors the reference's
 * generate_summary() return shape. */
public record SummaryResult(String text, String source) {
}
