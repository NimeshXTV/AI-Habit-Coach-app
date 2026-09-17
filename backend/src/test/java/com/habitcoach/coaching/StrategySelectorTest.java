package com.habitcoach.coaching;

import com.habitcoach.journey.DayStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every expected strategy below was captured by running the untouched
 * reference's choose_strategy() directly against the equivalent history,
 * via:
 *   PYTHONPATH=~/habit-coach/backend python3 -c "from intervention_engine import HabitState, choose_strategy; ..."
 * These are golden-master values, not hand-derived — see the scenario
 * names for what each one is verifying.
 */
class StrategySelectorTest {

    private static DaySnapshot day(int dayNumber, DayStatus status, String feedbackReason) {
        return new DaySnapshot(dayNumber, status, feedbackReason, 0, null);
    }

    @Test
    void day1WithNoHistoryIsEncouragement() {
        assertThat(StrategySelector.choose(1, 21, List.of())).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
    }

    @Test
    void day2AfterDoneDay1IsEncouragement() {
        List<DaySnapshot> history = List.of(day(1, DayStatus.DONE, null));
        assertThat(StrategySelector.choose(2, 21, history)).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
    }

    @Test
    void singleMissWithNoTimeInGettingStartedPhaseIsReduceTask() {
        // Only one occurrence of "no_time" so far -> not yet a reschedule signal.
        List<DaySnapshot> history = List.of(day(1, DayStatus.MISSED, "no_time"));
        assertThat(StrategySelector.choose(2, 21, history)).isEqualTo(InterventionStrategy.REDUCE_TASK);
    }

    @Test
    void repeatedNoTimeReasonTriggersReschedule() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "no_time"),
                day(2, DayStatus.MISSED, "no_time")
        );
        assertThat(StrategySelector.choose(3, 21, history)).isEqualTo(InterventionStrategy.RESCHEDULE);
    }

    @Test
    void repeatedTooTiredReasonTriggersReschedule() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "too_tired"),
                day(2, DayStatus.MISSED, "too_tired")
        );
        assertThat(StrategySelector.choose(3, 21, history)).isEqualTo(InterventionStrategy.RESCHEDULE);
    }

    @Test
    void twoConsecutiveMissesWithDifferentReasonsTriggerReflectionNotReschedule() {
        // Different reasons each day -> not a reschedule signal (that needs
        // the SAME reason repeating), but two misses in a row is its own,
        // higher-priority signal: reflection.
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "forgot"),
                day(2, DayStatus.MISSED, "something_came_up")
        );
        assertThat(StrategySelector.choose(3, 21, history)).isEqualTo(InterventionStrategy.REFLECTION);
    }

    @Test
    void missedWithNotFeelingItReasonIsReduceTaskRegardlessOfPhase() {
        List<DaySnapshot> history = List.of(day(1, DayStatus.MISSED, "not_feeling_it"));
        assertThat(StrategySelector.choose(2, 21, history)).isEqualTo(InterventionStrategy.REDUCE_TASK);
    }

    @Test
    void missedWithForgotReasonIsReduceTaskRegardlessOfPhase() {
        List<DaySnapshot> history = List.of(day(1, DayStatus.MISSED, "forgot"));
        assertThat(StrategySelector.choose(2, 21, history)).isEqualTo(InterventionStrategy.REDUCE_TASK);
    }

    @Test
    void missedWithOtherReasonInHandlingResistancePhaseIsReduceTask() {
        List<DaySnapshot> history = List.of(day(9, DayStatus.MISSED, "other"));
        assertThat(StrategySelector.choose(10, 21, history)).isEqualTo(InterventionStrategy.REDUCE_TASK);
    }

    @Test
    void missedWithOtherReasonInReinforcementPhaseIsAccountability() {
        List<DaySnapshot> history = List.of(day(15, DayStatus.MISSED, "other"));
        assertThat(StrategySelector.choose(16, 21, history)).isEqualTo(InterventionStrategy.ACCOUNTABILITY);
    }

    @Test
    void snoozedLastInBuildingConsistencyPhaseIsAccountability() {
        // Phase isn't getting_started/handling_resistance, and the reason
        // isn't not_feeling_it/forgot, so this falls through to accountability
        // rather than reduce_task.
        List<DaySnapshot> history = List.of(day(4, DayStatus.SNOOZED, "other"));
        assertThat(StrategySelector.choose(5, 21, history)).isEqualTo(InterventionStrategy.ACCOUNTABILITY);
    }

    @Test
    void milestoneDaySevenIsReinforcement() {
        List<DaySnapshot> history = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            history.add(day(d, DayStatus.DONE, null));
        }
        assertThat(StrategySelector.choose(7, 21, history)).isEqualTo(InterventionStrategy.REINFORCEMENT);
    }

    @Test
    void milestoneDayFourteenIsReinforcement() {
        List<DaySnapshot> history = new ArrayList<>();
        for (int d = 1; d <= 13; d++) {
            history.add(day(d, DayStatus.DONE, null));
        }
        assertThat(StrategySelector.choose(14, 21, history)).isEqualTo(InterventionStrategy.REINFORCEMENT);
    }

    @Test
    void milestoneDayTwentyOneIsReinforcementEvenThoughPhaseIsCompletion() {
        List<DaySnapshot> history = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            history.add(day(d, DayStatus.DONE, null));
        }
        assertThat(StrategySelector.choose(21, 21, history)).isEqualTo(InterventionStrategy.REINFORCEMENT);
    }

    @Test
    void nonMilestoneDayInReinforcementPhaseIsStillReinforcement() {
        // Day 16 isn't 7/14/21, but its ratio (16/21) lands in the
        // reinforcement phase, which alone is enough to trigger it.
        List<DaySnapshot> history = List.of(day(15, DayStatus.DONE, null));
        assertThat(StrategySelector.choose(16, 21, history)).isEqualTo(InterventionStrategy.REINFORCEMENT);
    }

    @Test
    void threeNonConsecutiveMissesInLastFiveTriggersReflection() {
        // Last day is DONE (so the immediate missed/snoozed branch and the
        // consecutive-missed check both stay clear), but 3 of the last 5
        // entries were missed -> reflection.
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "other"),
                day(2, DayStatus.MISSED, "other"),
                day(3, DayStatus.DONE, null),
                day(4, DayStatus.MISSED, "other"),
                day(5, DayStatus.DONE, null)
        );
        assertThat(StrategySelector.choose(6, 21, history)).isEqualTo(InterventionStrategy.REFLECTION);
    }

    @Test
    void twoConsecutiveMissedAtTheTailTriggersReflectionEvenWithEarlierNoise() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.DONE, null),
                day(2, DayStatus.MISSED, "no_time"),
                day(3, DayStatus.DONE, null),
                day(4, DayStatus.MISSED, "other"),
                day(5, DayStatus.MISSED, "forgot")
        );
        assertThat(StrategySelector.choose(6, 21, history)).isEqualTo(InterventionStrategy.REFLECTION);
    }

    @Test
    void plainEncouragementWhenNothingSpecialIsHappening() {
        List<DaySnapshot> history = List.of(day(4, DayStatus.DONE, null));
        assertThat(StrategySelector.choose(5, 21, history)).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
    }

    @Test
    void phaseThresholdsAreFixedFractionsNotRescaledForA14DayHabit() {
        // day 3 of a 14-day habit lands in building_consistency (ratio
        // 3/14 > the fixed 3/21 threshold), so a plain done-yesterday
        // history still resolves to encouragement, not something phase-
        // dependent flipping unexpectedly.
        List<DaySnapshot> history = List.of(day(2, DayStatus.DONE, null));
        assertThat(StrategySelector.choose(3, 14, history)).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
    }

    @Test
    void currentStreakCountsTrailingDoneDaysOnly() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "other"),
                day(2, DayStatus.DONE, null),
                day(3, DayStatus.DONE, null),
                day(4, DayStatus.DONE, null)
        );
        assertThat(StrategySelector.currentStreak(history)).isEqualTo(3);
    }

    @Test
    void consecutiveMissedCountsTrailingMissedDaysOnly() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.DONE, null),
                day(2, DayStatus.MISSED, "other"),
                day(3, DayStatus.MISSED, "other")
        );
        assertThat(StrategySelector.consecutiveMissed(history)).isEqualTo(2);
    }

    @Test
    void pendingOrSnoozedEntriesDoNotBreakAStreakOrMissCount() {
        List<DaySnapshot> history = List.of(
                day(1, DayStatus.MISSED, "other"),
                day(2, DayStatus.SNOOZED, "other")
        );
        // SNOOZED doesn't break the missed-streak count (only DONE does).
        assertThat(StrategySelector.consecutiveMissed(history)).isEqualTo(1);
    }
}
