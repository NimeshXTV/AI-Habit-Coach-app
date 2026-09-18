package com.habitcoach.advisor;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Body of POST /api/habits/{habitId}/advisor/message. `message` presence is
 * enforced here (matching GoalRequest's @NotNull convention); blankness is
 * checked in AdvisorService so the exact error message stays consistent
 * with the rest of the codebase's InvalidRequestException usage.
 * `history` is the recent conversation the mobile app already has
 * persisted locally for this habit (see mobile's advisorStore.ts) — it is
 * optional (a brand-new conversation sends none) and is bounded again
 * server-side regardless of what the client sends (see
 * AdvisorService.MAX_HISTORY_TURNS).
 */
public record AdvisorMessageRequest(@NotNull String message, List<AdvisorTurn> history) {

    public List<AdvisorTurn> historyOrEmpty() {
        return history != null ? history : List.of();
    }
}
