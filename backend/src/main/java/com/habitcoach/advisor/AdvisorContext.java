package com.habitcoach.advisor;

import java.util.List;

/**
 * Everything an AdvisorProvider needs to answer one message: the user's
 * new message plus the bounded recent conversation, and the same kind of
 * habit-history facts CoachingService already builds for the intervention/
 * response engine (day, streak, consecutive misses, recent feedback
 * reasons, last strategy) plus onboarding profile facts, reused via the
 * same shared utilities (StrategySelector, FeedbackLabels, AgeGroup) rather
 * than duplicated logic. Nullable fields (habitEmoji aside) reflect data
 * that may genuinely not exist yet (no profile saved, no strategy chosen
 * on day 1, no feedback reasons at all).
 */
public record AdvisorContext(
        Long habitId,
        String habitName,
        String habitEmoji,
        int dayNumber,
        int totalDays,
        int currentStreak,
        int consecutiveMissed,
        List<String> recentFeedbackReasons,
        String lastStrategy,
        String userName,
        String ageGroup,
        String userGender,
        String userMessage,
        List<AdvisorTurn> history
) {
}
