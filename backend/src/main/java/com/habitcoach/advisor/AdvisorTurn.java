package com.habitcoach.advisor;

/**
 * One turn of a Habit Advisor conversation, as sent by the mobile app's
 * locally-persisted, per-habitId conversation history (see mobile's
 * advisorStore.ts). `role` is "user" or "advisor" — kept as a plain string
 * rather than an enum since its only current consumer is a future LLM
 * prompt formatter, not any branching logic in this backend.
 */
public record AdvisorTurn(String role, String text) {
}
