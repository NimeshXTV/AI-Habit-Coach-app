package com.habitcoach.habit;

import com.habitcoach.action.ActionRequest;
import com.habitcoach.action.ActionResult;
import com.habitcoach.action.ActionService;
import com.habitcoach.coaching.CoachingService;
import com.habitcoach.coaching.CurrentInterventionResponse;
import com.habitcoach.coaching.InterventionResult;
import com.habitcoach.coaching.InterventionStrategy;
import com.habitcoach.coaching.SummaryResult;
import com.habitcoach.coaching.TimeFormat;
import com.habitcoach.goal.GoalConfirmRequest;
import com.habitcoach.goal.GoalRequest;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.JourneyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Habit CRUD routes, path-and-verb-compatible with the reference
 * implementation's main.py so the mobile app's src/api.ts needs no changes.
 * Goal parsing (POST /api/habits/parse) lives in GoalController — this
 * class owns the routes that persist a Habit.
 */
@RestController
@RequestMapping("/api/habits")
public class HabitController {

    private final HabitService habitService;
    private final JourneyService journeyService;
    private final CoachingService coachingService;
    private final ActionService actionService;

    public HabitController(HabitService habitService, JourneyService journeyService, CoachingService coachingService,
                            ActionService actionService) {
        this.habitService = habitService;
        this.journeyService = journeyService;
        this.coachingService = coachingService;
        this.actionService = actionService;
    }

    @GetMapping
    public List<Habit> listHabits(@RequestHeader("X-Device-Id") String deviceId) {
        return habitService.listHabits(deviceId);
    }

    @PostMapping
    public Habit createHabit(@RequestHeader("X-Device-Id") String deviceId, @Valid @RequestBody GoalRequest body) {
        return habitService.createFromText(deviceId, body.text());
    }

    @PostMapping("/confirm")
    public Habit confirmHabit(@RequestHeader("X-Device-Id") String deviceId, @Valid @RequestBody GoalConfirmRequest body) {
        return habitService.createConfirmed(deviceId, body.text(), body.timeOfDay());
    }

    @GetMapping("/{id}")
    public HabitDetailResponse getHabitDetail(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id) {
        Habit habit = habitService.getHabit(deviceId, id);
        return new HabitDetailResponse(habit, journeyService.getDays(id));
    }

    /**
     * Port of main.py's GET /api/habits/{habit_id}/current. Note this is a
     * GET that still writes (records the generated intervention on the
     * day's row) — an unusual REST shape, but a faithful port of the
     * reference's own behavior, not something introduced here.
     */
    @GetMapping("/{id}/current")
    public CurrentInterventionResponse getCurrent(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id) {
        Habit habit = habitService.getHabit(deviceId, id);
        List<HabitDay> days = journeyService.getDays(id);
        int dayNumber = JourneyService.currentDayNumber(days);

        if (habit.getStatus() != HabitStatus.ACTIVE) {
            SummaryResult summary = coachingService.generateSummary(habit, days);
            return CurrentInterventionResponse.finished(habit, dayNumber, summary.text(), summary.source());
        }

        InterventionResult result = coachingService.generateIntervention(deviceId, habit, dayNumber, days);
        journeyService.recordIntervention(id, dayNumber, result.text(), result.strategy().toJson());

        String suggestedTime = result.strategy() == InterventionStrategy.RESCHEDULE
                ? TimeFormat.suggestAlternateTime(habit.getTimeOfDay())
                : null;

        return CurrentInterventionResponse.active(habit, dayNumber, habit.getTotalDays(), result.text(),
                result.strategy().toJson(), result.source(), suggestedTime);
    }

    /** Port of main.py's POST /api/habits/{habit_id}/action — DONE/SNOOZE/MISSED. */
    @PostMapping("/{id}/action")
    public ActionResult postAction(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id,
                                    @Valid @RequestBody ActionRequest body) {
        return actionService.recordAction(deviceId, id, body.action(), body.feedbackReason(), body.feedbackNote());
    }

    /**
     * Port of main.py's POST /api/habits/{habit_id}/schedule — the only
     * endpoint that changes a habit's time_of_day. Returns the full
     * updated Habit (the authoritative schedule), same shape as
     * create/confirm.
     */
    @PostMapping("/{id}/schedule")
    public Habit updateSchedule(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id,
                                 @Valid @RequestBody ScheduleRequest body) {
        return habitService.updateSchedule(deviceId, id, body.timeOfDay());
    }

    /** Port of main.py's POST /api/habits/{id}/continue — Day-21 "start
     * another 21 days". Mobile never sends a body; the request DTO is
     * entirely optional to match the reference's `goal: GoalIn = None`. */
    @PostMapping("/{id}/continue")
    public Habit continueHabit(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id,
                                @RequestBody(required = false) GoalRequest body) {
        return habitService.continueHabit(deviceId, id, body != null ? body.text() : null);
    }

    /** Port of main.py's POST /api/habits/{id}/stop. */
    @PostMapping("/{id}/stop")
    public Map<String, Boolean> stopHabit(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id) {
        habitService.stopHabit(deviceId, id);
        return Map.of("ok", true);
    }

    /** Port of main.py's DELETE /api/habits/{id} — used by the mobile
     * app's My Challenges delete action. */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> deleteHabit(@RequestHeader("X-Device-Id") String deviceId, @PathVariable Long id) {
        habitService.deleteHabit(deviceId, id);
        return Map.of("ok", true);
    }
}
