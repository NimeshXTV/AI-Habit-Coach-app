package com.habitcoach.coaching;

import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;

/**
 * Immutable, detached view of a HabitDay used only for coaching
 * computations. Deliberately NOT the JPA entity itself: strategy selection
 * needs to synthesize a "snoozed" status for the current day that never
 * gets persisted (see CoachingService.buildHistory(), porting main.py's
 * _build_state()) — doing that on a managed entity would risk an
 * accidental write via Hibernate dirty checking. A plain copy makes that
 * impossible by construction.
 */
public record DaySnapshot(
        int dayNumber,
        DayStatus status,
        String feedbackReason,
        Integer snoozeCount,
        String interventionStrategy
) {

    public static DaySnapshot from(HabitDay day) {
        return new DaySnapshot(day.getDayNumber(), day.getStatus(), day.getFeedbackReason(),
                day.getSnoozeCount(), day.getInterventionStrategy());
    }

    public DaySnapshot asSnoozed() {
        return new DaySnapshot(dayNumber, DayStatus.SNOOZED, feedbackReason, snoozeCount, interventionStrategy);
    }
}
