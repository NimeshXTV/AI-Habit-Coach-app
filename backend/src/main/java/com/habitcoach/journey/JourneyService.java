package com.habitcoach.journey;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Owns the HabitDay lifecycle. initializeDays() is the Java port of
 * legacy-reference/backend/db.py's create_habit(), which pre-creates every
 * day row as 'pending' up front rather than lazily as days are reached —
 * ported unchanged since the rest of the adaptive engine (not yet ported)
 * relies on being able to read the full days-so-far list at any point.
 */
@Service
public class JourneyService {

    private final HabitDayRepository habitDayRepository;

    public JourneyService(HabitDayRepository habitDayRepository) {
        this.habitDayRepository = habitDayRepository;
    }

    public List<HabitDay> initializeDays(Long habitId, int totalDays) {
        List<HabitDay> days = new ArrayList<>(totalDays);
        for (int dayNumber = 1; dayNumber <= totalDays; dayNumber++) {
            days.add(new HabitDay(habitId, dayNumber));
        }
        return habitDayRepository.saveAll(days);
    }

    public List<HabitDay> getDays(Long habitId) {
        return habitDayRepository.findByHabitIdOrderByDayNumber(habitId);
    }

    /**
     * Port of legacy-reference/backend/db.py's get_current_day_number():
     * the first PENDING day, or the last day if every day is already
     * resolved, or 1 if there are no days at all. Takes the already-loaded,
     * ascending-by-day_number list rather than re-querying, since callers
     * (CoachingController) already have it in hand.
     */
    public static int currentDayNumber(List<HabitDay> daysAscending) {
        for (HabitDay day : daysAscending) {
            if (day.getStatus() == DayStatus.PENDING) {
                return day.getDayNumber();
            }
        }
        if (daysAscending.isEmpty()) {
            return 1;
        }
        return daysAscending.get(daysAscending.size() - 1).getDayNumber();
    }

    /** Port of db.py's record_intervention(): stashes the generated nudge
     * on that day's row for later inspection/debugging. Does not affect
     * status or any strategy-selection logic. */
    public void recordIntervention(Long habitId, int dayNumber, String text, String strategy) {
        habitDayRepository.findByHabitIdAndDayNumber(habitId, dayNumber).ifPresent(day -> {
            day.setInterventionText(text);
            day.setInterventionStrategy(strategy);
            habitDayRepository.save(day);
        });
    }

    /**
     * Port of db.py's record_action(): updates status/action/feedback on
     * the given day, bumps snooze_count for a "snoozed" action, and leaves
     * intervention_text/intervention_strategy completely untouched — those
     * columns were set by a prior /current call (if any) and this update
     * deliberately never overwrites them, exactly like the reference's SQL
     * UPDATE only naming the columns it changes.
     *
     * Returns true if, after this update, no PENDING days remain for the
     * habit — the caller (ActionService) uses this to decide whether to
     * mark the habit COMPLETED, mirroring record_action's inline check.
     */
    @Transactional
    public boolean recordAction(Long habitId, int dayNumber, String action, String feedbackReason, String feedbackNote) {
        HabitDay day = habitDayRepository.findByHabitIdAndDayNumber(habitId, dayNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "habit_day not found for habitId=" + habitId + " dayNumber=" + dayNumber));

        Instant completedAt = "done".equals(action) ? Instant.now() : null;
        DayStatus status = switch (action) {
            case "done" -> DayStatus.DONE;
            case "missed" -> DayStatus.MISSED;
            default -> DayStatus.PENDING; // "snoozed" is deliberately NOT terminal
        };

        day.setStatus(status);
        day.setAction(action);
        day.setFeedbackReason(feedbackReason);
        day.setFeedbackNote(feedbackNote);
        day.setCompletedAt(completedAt);
        if ("snoozed".equals(action)) {
            day.setSnoozeCount(day.getSnoozeCount() + 1);
        }
        habitDayRepository.save(day);

        return habitDayRepository.countByHabitIdAndStatus(habitId, DayStatus.PENDING) == 0;
    }

    /** Port of db.py's delete_habit()'s day-row half: "DELETE FROM
     * habit_days WHERE habit_id = ?". Called before the habit row itself
     * is removed (see HabitService.deleteHabit) — same order the
     * reference uses, even though no DB-level FK constraint enforces it
     * here (see Habit/HabitDay's javadoc on that scaffold-stage choice). */
    @Transactional
    public void deleteAllDays(Long habitId) {
        habitDayRepository.deleteByHabitId(habitId);
    }
}
