package com.habitcoach.action;

import com.habitcoach.coaching.CoachingService;
import com.habitcoach.goal.GoalParser;
import com.habitcoach.habit.Habit;
import com.habitcoach.habit.HabitRepository;
import com.habitcoach.habit.HabitService;
import com.habitcoach.habit.HabitStatus;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.HabitDayRepository;
import com.habitcoach.journey.JourneyService;
import com.habitcoach.profile.ProfileService;
import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsUnavailableException;
import com.habitcoach.web.InvalidRequestException;
import com.habitcoach.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises the real ActionService/JourneyService/HabitService pipeline
 * against a real (in-memory, for tests) database. StrandsClient is mocked
 * to always throw StrandsUnavailableException, forcing every response
 * through FallbackTemplates — which, unlike the pre-action nudge templates,
 * has NO randomness for action-response kinds, so exact text can be
 * asserted deterministically. Strands success/fallback selection itself is
 * covered by CoachingServiceTest.
 */
@DataJpaTest
@Import({HabitService.class, JourneyService.class, GoalParser.class, ActionService.class, CoachingService.class,
        ProfileService.class})
class ActionServiceTest {

    @Autowired
    private ActionService actionService;

    @Autowired
    private HabitService habitService;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private HabitDayRepository habitDayRepository;

    @MockBean
    private StrandsClient strandsClient;

    @BeforeEach
    void alwaysFallsBackToTemplate() {
        when(strandsClient.generate(anyString(), anyMap()))
                .thenThrow(new StrandsUnavailableException("no strands in this test", null));
    }

    private HabitDay day(Long habitId, int dayNumber) {
        return habitDayRepository.findByHabitIdAndDayNumber(habitId, dayNumber).orElseThrow();
    }

    @Test
    void doneOnDay1ProducesCompletionFirstAndMarksDayDone() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "done", null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.dayNumber()).isEqualTo(1);
        assertThat(result.responseKind()).isEqualTo("completion_first");
        assertThat(result.generatedBy()).isEqualTo("template");
        assertThat(result.responseText()).isEqualTo(
                "You showed up. That's the hardest part of starting. Day 1 complete — you've officially begun.");
        assertThat(result.consecutiveMissedDays()).isEqualTo(0);

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.DONE);
        assertThat(day1.getAction()).isEqualTo("done");
        assertThat(day1.getCompletedAt()).isNotNull();
    }

    @Test
    void firstSnoozeIncrementsSnoozeCountAndKeepsDayPending() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "snoozed", "not_feeling_it", null);

        assertThat(result.dayNumber()).isEqualTo(1);
        assertThat(result.responseText()).isNull();
        assertThat(result.responseKind()).isNull();

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.PENDING);
        assertThat(day1.getAction()).isEqualTo("snoozed");
        assertThat(day1.getFeedbackReason()).isEqualTo("not_feeling_it");
        assertThat(day1.getSnoozeCount()).isEqualTo(1);
        assertThat(day1.getCompletedAt()).isNull();

        // day stays "current" -> the same day is still day_number 1
        assertThat(JourneyService.currentDayNumber(habitDayRepository.findByHabitIdOrderByDayNumber(habit.getId())))
                .isEqualTo(1);
    }

    @Test
    void secondSnoozeSameDayEscalatesSnoozeCountToTwo() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        actionService.recordAction("device-1", habit.getId(), "snoozed", "not_feeling_it", null);
        actionService.recordAction("device-1", habit.getId(), "snoozed", "not_feeling_it", null);

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getSnoozeCount()).isEqualTo(2);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.PENDING);
    }

    @Test
    void snoozingNeverChangesTheHabitsPermanentScheduledTime() {
        // The mobile client schedules a temporary snooze alarm under a
        // separate identifier from the permanent daily alarm (Priority 2/4:
        // "the permanent habit time MUST NOT change" / "snooze scheduling
        // must never overwrite the permanent daily schedule"). This asserts
        // the server-side half of that guarantee: recording any number of
        // 'snoozed' actions never touches habits.time_of_day.
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        String originalTime = habit.getTimeOfDay();

        actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);
        actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);

        Habit reloaded = habitRepository.findById(habit.getId()).orElseThrow();
        assertThat(reloaded.getTimeOfDay()).isEqualTo(originalTime);
    }

    @Test
    void thirdSnoozeSameDayFollowedByClientTriggeredMissedPersistsSnoozeCountAndMissedStatus() {
        // Mirrors the mobile client's flow (HabitScreen.handleSnoozeChoice): the
        // mobile app never lets a user snooze a 4th time in one day — after the
        // 3rd snoozed action it re-reads snooze_count and, on seeing it hit the
        // cap, immediately calls the same /action endpoint with "missed" itself.
        // This test verifies the backend sequence that produces, independent of
        // any mobile-side timer/polling: three snoozes escalate snooze_count to
        // 3 while the day stays pending, then the following missed action marks
        // the day MISSED and still returns a normal miss response.
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);
        actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);
        ActionResult thirdSnooze = actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);

        HabitDay afterThirdSnooze = day(habit.getId(), 1);
        assertThat(afterThirdSnooze.getSnoozeCount()).isEqualTo(3);
        assertThat(afterThirdSnooze.getStatus()).isEqualTo(DayStatus.PENDING);
        assertThat(thirdSnooze.responseText()).isNull();

        ActionResult autoMissed = actionService.recordAction("device-1", habit.getId(), "missed", "no_time", null);

        assertThat(autoMissed.ok()).isTrue();
        assertThat(autoMissed.responseKind()).isEqualTo("miss_single");

        HabitDay afterAutoMissed = day(habit.getId(), 1);
        assertThat(afterAutoMissed.getStatus()).isEqualTo(DayStatus.MISSED);
        assertThat(afterAutoMissed.getAction()).isEqualTo("missed");
        assertThat(afterAutoMissed.getSnoozeCount()).isEqualTo(3);
        assertThat(afterAutoMissed.getFeedbackReason()).isEqualTo("no_time");
    }

    @Test
    void missedRecordsReasonAndNoteAndProducesMissSingle() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "missed", "too_tired", "long day at work");

        assertThat(result.responseKind()).isEqualTo("miss_single");
        assertThat(result.responseText()).isEqualTo(
                "That's okay. One missed day doesn't erase the work you've already done. Let's get back on track tomorrow.");

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.MISSED);
        assertThat(day1.getAction()).isEqualTo("missed");
        assertThat(day1.getFeedbackReason()).isEqualTo("too_tired");
        assertThat(day1.getFeedbackNote()).isEqualTo("long day at work");
        assertThat(day1.getCompletedAt()).isNull();
    }

    @Test
    void everyFeedbackReasonIsAccepted() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        for (String reason : new String[]{"too_tired", "no_time", "forgot", "something_came_up", "not_feeling_it", "other"}) {
            Habit fresh = habitService.createFromText("device-1", "read every day at 6pm");
            ActionResult result = actionService.recordAction("device-1", fresh.getId(), "missed", reason, null);
            assertThat(result.ok()).isTrue();
            assertThat(day(fresh.getId(), 1).getFeedbackReason()).isEqualTo(reason);
        }
    }

    @Test
    void repeatedSameReasonAcrossDaysIsRecordedOnEachDay() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        actionService.recordAction("device-1", habit.getId(), "missed", "no_time", null);
        actionService.recordAction("device-1", habit.getId(), "missed", "no_time", null);

        assertThat(day(habit.getId(), 1).getFeedbackReason()).isEqualTo("no_time");
        assertThat(day(habit.getId(), 2).getFeedbackReason()).isEqualTo("no_time");
        // day 3 is now current
        assertThat(JourneyService.currentDayNumber(habitDayRepository.findByHabitIdOrderByDayNumber(habit.getId())))
                .isEqualTo(3);
    }

    @Test
    void twoConsecutiveMissesProduceMissConsecutiveOnTheSecond() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        ActionResult first = actionService.recordAction("device-1", habit.getId(), "missed", "forgot", null);
        ActionResult second = actionService.recordAction("device-1", habit.getId(), "missed", "something_came_up", null);

        assertThat(first.responseKind()).isEqualTo("miss_single");
        assertThat(first.consecutiveMissedDays()).isEqualTo(1);
        assertThat(second.responseKind()).isEqualTo("miss_consecutive");
        assertThat(second.consecutiveMissedDays()).isEqualTo(2);
    }

    @Test
    void doneAfterAMissedDayIsCompletionRecovery() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        actionService.recordAction("device-1", habit.getId(), "missed", "no_time", null);

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "done", null, null);

        assertThat(result.responseKind()).isEqualTo("completion_recovery");
    }

    @Test
    void doneOnFinalDayMarksHabitCompleted() {
        Habit habit = habitService.createFromText("device-1", "read every night for 3 days");
        actionService.recordAction("device-1", habit.getId(), "done", null, null);
        actionService.recordAction("device-1", habit.getId(), "done", null, null);

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "done", null, null);

        assertThat(result.responseKind()).isEqualTo("completion_final");
        Habit reloaded = habitRepository.findById(habit.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HabitStatus.COMPLETED);
    }

    @Test
    void missedOnFinalDayAlsoMarksHabitCompleted() {
        // The journey ends after total_days regardless of whether the last
        // day was done or missed — matches db.py's unconditional "no
        // pending days remain" check.
        Habit habit = habitService.createFromText("device-1", "read every night for 2 days");
        actionService.recordAction("device-1", habit.getId(), "done", null, null);

        actionService.recordAction("device-1", habit.getId(), "missed", "forgot", null);

        Habit reloaded = habitRepository.findById(habit.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HabitStatus.COMPLETED);
    }

    @Test
    void snoozeNeverMarksHabitCompletedEvenOnLastDay() {
        Habit habit = habitService.createFromText("device-1", "read every night for 1 days");

        actionService.recordAction("device-1", habit.getId(), "snoozed", "forgot", null);

        Habit reloaded = habitRepository.findById(habit.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HabitStatus.ACTIVE);
    }

    @Test
    void multipleHabitsTrackActionsIndependently() {
        Habit gym = habitService.createFromText("device-1", "gym every day at 6pm");
        Habit read = habitService.createFromText("device-1", "read every night for 14 days");

        actionService.recordAction("device-1", gym.getId(), "missed", "no_time", null);
        actionService.recordAction("device-1", read.getId(), "done", null, null);

        assertThat(day(gym.getId(), 1).getStatus()).isEqualTo(DayStatus.MISSED);
        assertThat(day(read.getId(), 1).getStatus()).isEqualTo(DayStatus.DONE);
        assertThat(JourneyService.currentDayNumber(habitDayRepository.findByHabitIdOrderByDayNumber(gym.getId())))
                .isEqualTo(2);
        assertThat(JourneyService.currentDayNumber(habitDayRepository.findByHabitIdOrderByDayNumber(read.getId())))
                .isEqualTo(2);
    }

    @Test
    void invalidActionIsRejected() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.recordAction("device-1", habit.getId(), "finished", null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("action must be done | snoozed | missed");
    }

    @Test
    void invalidFeedbackReasonIsRejected() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.recordAction("device-1", habit.getId(), "missed", "excuses", null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("feedback_reason must be one of ['too_tired', 'no_time', 'forgot', 'something_came_up', 'not_feeling_it', 'other']");
    }

    @Test
    void emptyFeedbackReasonIsAllowedThrough() {
        // Mirrors the reference's falsy-string check: `if body.feedback_reason and ...`
        // skips validation entirely for an empty string.
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        ActionResult result = actionService.recordAction("device-1", habit.getId(), "missed", "", null);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void unknownHabitThrowsNotFound() {
        assertThatThrownBy(() -> actionService.recordAction("device-1", 999_999L, "done", null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("habit not found");
    }

    @Test
    void interventionTextRecordedByAPriorCurrentCallSurvivesAnAction() {
        // Preservation requirement: recording an action must never clobber
        // intervention_text/intervention_strategy set by a prior /current
        // call — db.py's record_action UPDATE never names those columns.
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        habitDayRepository.findByHabitIdAndDayNumber(habit.getId(), 1).ifPresent(d -> {
            d.setInterventionText("Some previously generated nudge");
            d.setInterventionStrategy("encouragement");
            habitDayRepository.save(d);
        });

        actionService.recordAction("device-1", habit.getId(), "done", null, null);

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getInterventionText()).isEqualTo("Some previously generated nudge");
        assertThat(day1.getInterventionStrategy()).isEqualTo("encouragement");
    }

    // ---- done/missed independence across habits (tree gamification removed;
    // this coverage is what's left once the tree-specific assertions are
    // stripped out — separate habits' done/missed actions must still never
    // cross-contaminate each other's day/status state) ----

    @Test
    void separateHabitsRecordActionsIndependently() {
        Habit gym = habitService.createFromText("device-1", "gym every day at 6pm");
        Habit read = habitService.createFromText("device-1", "read every night for 14 days");

        actionService.recordAction("device-1", gym.getId(), "missed", "no_time", null);
        actionService.recordAction("device-1", read.getId(), "done", null, null);

        assertThat(day(gym.getId(), 1).getStatus()).isEqualTo(DayStatus.MISSED);
        assertThat(day(read.getId(), 1).getStatus()).isEqualTo(DayStatus.DONE);
    }

    // ---- editDayStatus: manual calendar-tap correction ----

    @Test
    void editDayStatusOnUnknownHabitThrowsNotFound() {
        assertThatThrownBy(() -> actionService.editDayStatus("device-1", 999999L, 1, "done"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void editDayStatusFromWrongDeviceThrowsNotFound() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.editDayStatus("a-different-device", habit.getId(), 1, "done"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void editDayStatusRejectsInvalidStatusValue() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.editDayStatus("device-1", habit.getId(), 1, "snoozed"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("status must be pending | done | missed");
    }

    @Test
    void editDayStatusRejectsDayNumberBelowOne() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.editDayStatus("device-1", habit.getId(), 0, "done"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("day_number out of range");
    }

    @Test
    void editDayStatusRejectsDayNumberAboveTotalDays() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.editDayStatus("device-1", habit.getId(), 22, "done"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("day_number out of range");
    }

    @Test
    void editDayStatusRejectsAFutureDay() {
        // A fresh habit's current day is 1 — day 2 hasn't been reached yet,
        // regardless of it being "in range" (1..totalDays).
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        assertThatThrownBy(() -> actionService.editDayStatus("device-1", habit.getId(), 2, "done"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("cannot edit a day that hasn't been reached yet");
    }

    @Test
    void editDayStatusRejectsANonActiveHabit() {
        Habit habit = habitService.createFromText("device-1", "read every night for 1 days");
        actionService.recordAction("device-1", habit.getId(), "done", null, null); // completes the 1-day habit

        assertThatThrownBy(() -> actionService.editDayStatus("device-1", habit.getId(), 1, "missed"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("only an active challenge's days can be edited");
    }

    @Test
    void editDayStatusMarkDoneSetsStatusAndCompletedAtLeavesOtherFieldsUntouched() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        // Give day 1 some history first, via the normal snooze flow, to prove
        // the edit below leaves it alone.
        actionService.recordAction("device-1", habit.getId(), "snoozed", "no_time", null);

        DayStatusEditResult result = actionService.editDayStatus("device-1", habit.getId(), 1, "done");

        assertThat(result.ok()).isTrue();
        assertThat(result.dayNumber()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("done");

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.DONE);
        assertThat(day1.getCompletedAt()).isNotNull();
        // untouched by the edit:
        assertThat(day1.getAction()).isEqualTo("snoozed");
        assertThat(day1.getFeedbackReason()).isEqualTo("no_time");
        assertThat(day1.getSnoozeCount()).isEqualTo(1);
    }

    @Test
    void editDayStatusMarkMissedClearsCompletedAt() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        actionService.editDayStatus("device-1", habit.getId(), 1, "done");
        assertThat(day(habit.getId(), 1).getCompletedAt()).isNotNull();

        actionService.editDayStatus("device-1", habit.getId(), 1, "missed");

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.MISSED);
        assertThat(day1.getCompletedAt()).isNull();
    }

    @Test
    void editDayStatusResetToPendingClearsCompletedAtAndShiftsCurrentDayNumberBackward() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");
        actionService.recordAction("device-1", habit.getId(), "done", null, null); // day 1 done, current day is now 2

        actionService.editDayStatus("device-1", habit.getId(), 1, "pending");

        HabitDay day1 = day(habit.getId(), 1);
        assertThat(day1.getStatus()).isEqualTo(DayStatus.PENDING);
        assertThat(day1.getCompletedAt()).isNull();
        assertThat(JourneyService.currentDayNumber(habitDayRepository.findByHabitIdOrderByDayNumber(habit.getId())))
                .isEqualTo(1);
    }

    @Test
    void editDayStatusOnFinalPendingDayMarksHabitCompleted() {
        Habit habit = habitService.createFromText("device-1", "read every night for 2 days");
        actionService.recordAction("device-1", habit.getId(), "done", null, null); // day 1 done via the normal flow

        actionService.editDayStatus("device-1", habit.getId(), 2, "done"); // day 2 resolved via the edit path instead

        Habit reloaded = habitService.getHabit("device-1", habit.getId());
        assertThat(reloaded.getStatus()).isEqualTo(HabitStatus.COMPLETED);
    }

    @Test
    void editDayStatusResultCarriesNoCoachingFields() {
        Habit habit = habitService.createFromText("device-1", "gym every day at 6pm");

        DayStatusEditResult result = actionService.editDayStatus("device-1", habit.getId(), 1, "missed");

        // The DTO shape itself has no responseText/responseKind/generatedBy
        // fields (unlike ActionResult) — nothing to assert null on, which is
        // the point: this path can never carry a coaching-generated reply.
        assertThat(result).isEqualTo(new DayStatusEditResult(true, 1, "missed"));
    }
}
