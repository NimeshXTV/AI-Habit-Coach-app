package com.habitcoach.coaching;

import com.habitcoach.journey.DayStatus;

import java.util.List;

/**
 * The adaptive core — a line-for-line port of legacy-reference/backend/
 * intervention_engine.py's choose_strategy() (plus its private helpers
 * _repeated_reason, _consecutive_missed, _current_streak). Deliberately
 * deterministic, no AI call: this is the part of the pitch that must stay
 * fully inspectable, and per the architecture, this decision belongs to
 * Spring Boot, not Strands.
 *
 * `days` here is always the properly-scoped history: entries with
 * dayNumber < the current day, in ascending order, plus (if the current
 * day was itself snoozed) one synthetic trailing SNOOZED entry — see
 * CoachingService.buildHistory(), which ports main.py's _build_state().
 * Passing the full day list (including untouched future PENDING rows)
 * instead would silently break the "3+ misses in the last 5" check, since
 * that check looks at the LAST 5 entries of whatever list it's given.
 */
public final class StrategySelector {

    private StrategySelector() {
    }

    public static InterventionStrategy choose(int dayNumber, int totalDays, List<DaySnapshot> days) {
        Phase phase = Phase.of(dayNumber, totalDays);
        DaySnapshot lastDay = days.isEmpty() ? null : days.get(days.size() - 1);

        // Day 1, first attempt — always reduce friction, nothing to learn from yet.
        if (dayNumber == 1 && lastDay == null) {
            return InterventionStrategy.ENCOURAGEMENT;
        }

        // Strongest signal: same failure reason repeating -> the schedule itself is wrong.
        if (repeatedReason(days, "no_time") || repeatedReason(days, "too_tired")) {
            return InterventionStrategy.RESCHEDULE;
        }

        // Two misses in a row (any reason, or mixed reasons) -> proactively address
        // the pattern rather than quietly repeating the "shrink the task" nudge.
        if (consecutiveMissed(days) >= 2) {
            return InterventionStrategy.REFLECTION;
        }

        // Just missed/snoozed last time -> meet them where they are, shrink the ask.
        if (lastDay != null && (lastDay.status() == DayStatus.MISSED || lastDay.status() == DayStatus.SNOOZED)) {
            String reason = lastDay.feedbackReason();
            if ("not_feeling_it".equals(reason) || "forgot".equals(reason)) {
                return InterventionStrategy.REDUCE_TASK;
            }
            return (phase == Phase.GETTING_STARTED || phase == Phase.HANDLING_RESISTANCE)
                    ? InterventionStrategy.REDUCE_TASK
                    : InterventionStrategy.ACCOUNTABILITY;
        }

        // Multiple misses this window but not immediately last -> ask what's going on.
        List<DaySnapshot> recentWindow = recent(days, 5);
        long missCount = recentWindow.stream()
                .filter(d -> d.status() == DayStatus.MISSED || d.status() == DayStatus.SNOOZED)
                .count();
        if (missCount >= 3) {
            return InterventionStrategy.REFLECTION;
        }

        // Milestone days -> reinforce progress.
        if (dayNumber == 7 || dayNumber == 14 || dayNumber == 21 || phase == Phase.REINFORCEMENT) {
            return InterventionStrategy.REINFORCEMENT;
        }

        // Doing fine -> plain encouragement, keep it light.
        return InterventionStrategy.ENCOURAGEMENT;
    }

    public static int currentStreak(List<DaySnapshot> days) {
        int count = 0;
        for (int i = days.size() - 1; i >= 0; i--) {
            DayStatus status = days.get(i).status();
            if (status == DayStatus.DONE) {
                count++;
            } else if (status == DayStatus.MISSED) {
                break;
            }
        }
        return count;
    }

    public static int consecutiveMissed(List<DaySnapshot> days) {
        int count = 0;
        for (int i = days.size() - 1; i >= 0; i--) {
            DayStatus status = days.get(i).status();
            if (status == DayStatus.MISSED) {
                count++;
            } else if (status == DayStatus.DONE) {
                break;
            }
            // a PENDING/SNOOZED entry (the same day retried) doesn't break the streak
        }
        return count;
    }

    private static boolean repeatedReason(List<DaySnapshot> days, String reason) {
        List<DaySnapshot> recentWindow = recent(days, 4);
        long count = recentWindow.stream().filter(d -> reason.equals(d.feedbackReason())).count();
        return count >= 2;
    }

    private static List<DaySnapshot> recent(List<DaySnapshot> days, int n) {
        if (days.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, days.size() - n);
        return days.subList(from, days.size());
    }
}
