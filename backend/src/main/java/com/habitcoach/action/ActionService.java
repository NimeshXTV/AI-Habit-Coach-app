package com.habitcoach.action;

import com.habitcoach.coaching.ActionResponseResult;
import com.habitcoach.coaching.CoachingService;
import com.habitcoach.coaching.FeedbackLabels;
import com.habitcoach.habit.Habit;
import com.habitcoach.habit.HabitService;
import com.habitcoach.habit.HabitStatus;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.web.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Port of legacy-reference/backend/main.py's POST /api/habits/{id}/action
 * route. Spring Boot remains the sole owner of habit/day state throughout:
 * this service validates, updates the day (and possibly the habit) via
 * JourneyService/HabitService, and only asks CoachingService (which in
 * turn calls the stateless Strands service) for the human-readable
 * response text — Strands never reads or writes any persisted state.
 */
@Service
public class ActionService {

    private static final Set<String> VALID_ACTIONS = Set.of("done", "snoozed", "missed");

    /** Valid targets for editDayStatus() — deliberately excludes "snoozed",
     * which stays alarm/native-driven and is never something a manual
     * calendar edit should set directly. */
    private static final Set<String> VALID_DAY_STATUSES = Set.of("pending", "done", "missed");

    /** Matches the reference's f"feedback_reason must be one of {list(FEEDBACK_LABELS)}"
     * for its exact key order (too_tired, no_time, forgot, something_came_up,
     * not_feeling_it, other) — hardcoded rather than derived from a Map,
     * since Map.of() does not guarantee iteration order. */
    private static final String FEEDBACK_REASON_ERROR =
            "feedback_reason must be one of ['too_tired', 'no_time', 'forgot', 'something_came_up', 'not_feeling_it', 'other']";

    private final HabitService habitService;
    private final JourneyService journeyService;
    private final CoachingService coachingService;

    public ActionService(HabitService habitService, JourneyService journeyService, CoachingService coachingService) {
        this.habitService = habitService;
        this.journeyService = journeyService;
        this.coachingService = coachingService;
    }

    @Transactional
    public ActionResult recordAction(String deviceId, Long habitId, String action, String feedbackReason, String feedbackNote) {
        Habit habit = habitService.getHabit(deviceId, habitId); // throws NotFoundException if missing or owned by a different device

        if (action == null || !VALID_ACTIONS.contains(action)) {
            throw new InvalidRequestException("action must be done | snoozed | missed");
        }
        boolean hasReason = feedbackReason != null && !feedbackReason.isEmpty();
        if (hasReason && !FeedbackLabels.LABELS.containsKey(feedbackReason)) {
            throw new InvalidRequestException(FEEDBACK_REASON_ERROR);
        }

        List<HabitDay> daysBefore = journeyService.getDays(habitId);
        int dayNumber = JourneyService.currentDayNumber(daysBefore);

        boolean journeyResolved = journeyService.recordAction(habitId, dayNumber, action, feedbackReason, feedbackNote);
        if (journeyResolved) {
            habitService.markCompleted(habit);
        }

        if ("done".equals(action) || "missed".equals(action)) {
            List<HabitDay> daysAfter = journeyService.getDays(habitId);
            ActionResponseResult response = coachingService.generateActionResponse(deviceId, habit, dayNumber, action, daysAfter);

            return ActionResult.withResponse(dayNumber, response.text(), response.kind().toJson(),
                    response.source(), response.consecutiveMissed());
        }

        return ActionResult.simple(dayNumber);
    }

    /**
     * Manual calendar-tap correction (POST /api/habits/{id}/days/{dayNumber}/status)
     * — distinct from recordAction() above: this is a direct status override
     * the user explicitly picks for a SPECIFIC day, not an action against
     * "whichever day is currently pending". Deliberately never calls
     * CoachingService/Strands (see DayStatusEditResult's javadoc) and never
     * touches action/feedbackReason/feedbackNote/interventionText/
     * interventionStrategy/snoozeCount (see JourneyService.setDayStatus).
     *
     * Editable range is [1, currentDayNumber] — day 1 is always the habit's
     * creation day (see JourneyService.initializeDays), so a day before that
     * simply doesn't exist as a row and dayNumber < 1 already rejects it;
     * there is no separate "before creation" case to special-case. A day
     * ahead of currentDayNumber hasn't been reached yet and stays rejected.
     *
     * markCompleted IS still reachable from here: the ACTIVE-only guard
     * below only blocks re-editing an already-completed habit, it doesn't
     * stop THIS edit from being the one that resolves the last pending day
     * (e.g. editing today's own final-day cell to "done") — so the same
     * completion invariant recordAction() maintains is preserved here too.
     */
    @Transactional
    public DayStatusEditResult editDayStatus(String deviceId, Long habitId, int dayNumber, String status) {
        Habit habit = habitService.getHabit(deviceId, habitId); // 404 if missing or owned by a different device

        if (status == null || !VALID_DAY_STATUSES.contains(status)) {
            throw new InvalidRequestException("status must be pending | done | missed");
        }
        if (habit.getStatus() != HabitStatus.ACTIVE) {
            throw new InvalidRequestException("only an active challenge's days can be edited");
        }
        if (dayNumber < 1 || dayNumber > habit.getTotalDays()) {
            throw new InvalidRequestException("day_number out of range");
        }
        int currentDayNumber = JourneyService.currentDayNumber(journeyService.getDays(habitId));
        if (dayNumber > currentDayNumber) {
            throw new InvalidRequestException("cannot edit a day that hasn't been reached yet");
        }

        boolean journeyResolved = journeyService.setDayStatus(habitId, dayNumber, DayStatus.fromJson(status));
        if (journeyResolved) {
            habitService.markCompleted(habit);
        }
        return new DayStatusEditResult(true, dayNumber, status);
    }
}
