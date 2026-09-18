package com.habitcoach.action;

import com.habitcoach.coaching.ActionResponseResult;
import com.habitcoach.coaching.CoachingService;
import com.habitcoach.coaching.FeedbackLabels;
import com.habitcoach.habit.Habit;
import com.habitcoach.habit.HabitService;
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
}
