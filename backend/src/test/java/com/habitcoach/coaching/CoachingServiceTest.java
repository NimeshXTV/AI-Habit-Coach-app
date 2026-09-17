package com.habitcoach.coaching;

import com.habitcoach.habit.Habit;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.profile.Gender;
import com.habitcoach.profile.ProfileService;
import com.habitcoach.profile.UserProfile;
import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsResponse;
import com.habitcoach.strands.StrandsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CoachingService's orchestration: facts-payload shape (a
 * golden master captured from the reference's _facts()), the
 * success-from-Strands path, and the fallback-on-unavailable path. Uses a
 * mocked StrandsClient so these run instantly with no network/process
 * dependency — the real HTTP wiring is covered separately by
 * StrandsClientTest and the manual live verification.
 */
class CoachingServiceTest {

    private StrandsClient strandsClient;
    private ProfileService profileService;
    private CoachingService coachingService;

    @BeforeEach
    void setUp() {
        strandsClient = mock(StrandsClient.class);
        // Unstubbed Optional-returning mock methods default to Optional.empty()
        // (Mockito's ReturnsEmptyValues) — every existing test below therefore
        // runs exactly as it did before profile facts existed, with zero
        // profile-derived keys added.
        profileService = mock(ProfileService.class);
        coachingService = new CoachingService(strandsClient, profileService);
    }

    private static HabitDay dayRow(Long habitId, int dayNumber, DayStatus status, String action,
                                    String feedbackReason, int snoozeCount, String interventionStrategy) {
        HabitDay day = new HabitDay(habitId, dayNumber);
        day.setStatus(status);
        day.setAction(action);
        day.setFeedbackReason(feedbackReason);
        day.setSnoozeCount(snoozeCount);
        day.setInterventionStrategy(interventionStrategy);
        return day;
    }

    /**
     * Golden master captured from the reference's _facts(), for the exact
     * same history/habit shape:
     *   day1: done, day2: missed/no_time, day3: snoozed/other (snooze_count=2,
     *   strategy=reduce_task), current day=4, time_of_day=18:30, minutes=30.
     * Expected facts (from running _facts() directly against the reference):
     *   {"name":"gym","day":4,"total":21,"completed":1,"minutes":30,
     *    "time_of_day":"6:30 PM","reason":"something else","current_streak":0,
     *    "consecutive_missed":1,"snooze_count_today":2,"last_strategy":"reduce_task"}
     */
    @Test
    void factsPayloadMatchesReferenceGoldenMaster() {
        Habit habit = new Habit("Gym", "🏋️", "18:30", 30, 21);
        List<HabitDay> allDays = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, "encouragement"),
                dayRow(1L, 2, DayStatus.MISSED, "missed", "no_time", 0, "encouragement"),
                dayRow(1L, 3, DayStatus.SNOOZED, "snoozed", "other", 2, "reduce_task"),
                dayRow(1L, 4, DayStatus.PENDING, null, null, 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap()))
                .thenReturn(new StrandsResponse("text", "strands"));

        coachingService.generateIntervention(habit, 4, allDays);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(anyString(), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();

        assertThat(facts.get("name")).isEqualTo("gym");
        assertThat(facts.get("day")).isEqualTo(4);
        assertThat(facts.get("total")).isEqualTo(21);
        assertThat(facts.get("completed")).isEqualTo(1);
        assertThat(facts.get("minutes")).isEqualTo(30);
        assertThat(facts.get("time_of_day")).isEqualTo("6:30 PM");
        assertThat(facts.get("reason")).isEqualTo("something else");
        assertThat(facts.get("current_streak")).isEqualTo(0);
        assertThat(facts.get("consecutive_missed")).isEqualTo(1);
        assertThat(facts.get("snooze_count_today")).isEqualTo(2);
        assertThat(facts.get("last_strategy")).isEqualTo("reduce_task");
    }

    @Test
    void generateInterventionUsesStrandsKeyMatchingChosenStrategy() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        when(strandsClient.generate(anyString(), anyMap()))
                .thenReturn(new StrandsResponse("Day 1 nudge", "strands"));

        InterventionResult result = coachingService.generateIntervention(habit, 1, List.of());

        assertThat(result.strategy()).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
        assertThat(result.text()).isEqualTo("Day 1 nudge");
        assertThat(result.source()).isEqualTo("strands");
        verify(strandsClient).generate(eq("encouragement"), anyMap());
    }

    @Test
    void generateInterventionFallsBackToTemplateWhenStrandsUnavailable() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        when(strandsClient.generate(anyString(), anyMap()))
                .thenThrow(new StrandsUnavailableException("connection refused", null));

        InterventionResult result = coachingService.generateIntervention(habit, 1, List.of());

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.strategy()).isEqualTo(InterventionStrategy.ENCOURAGEMENT);
        assertThat(result.text()).isIn(
                "Let's get started. Don't think about all 21 days — just focus on gym today.",
                "Day 1 of 21. Keep it simple: just begin. That's the whole goal today."
        );
    }

    @Test
    void generateSummarySuccessUsesSummaryKey() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDays = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 2, DayStatus.MISSED, "missed", "no_time", 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap()))
                .thenReturn(new StrandsResponse("Great run!", "strands"));

        SummaryResult result = coachingService.generateSummary(habit, allDays);

        assertThat(result.text()).isEqualTo("Great run!");
        assertThat(result.source()).isEqualTo("strands");
        verify(strandsClient).generate("summary", Map.of("name", "gym", "total", 21, "completed", 1));
    }

    @Test
    void generateSummaryFallsBackToTemplateWhenStrandsUnavailable() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDays = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap()))
                .thenThrow(new StrandsUnavailableException("timeout", null));

        SummaryResult result = coachingService.generateSummary(habit, allDays);

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.text()).isEqualTo(
                "You completed 1 out of 21 days of gym. Want to continue this habit, start a new 21-day challenge, or stop here?");
    }

    @Test
    void snoozedCurrentDaySynthesizesTrailingSnoozedEntryForStrategySelection() {
        // Day 3 was just snoozed (action="snoozed"); its DB status is still
        // PENDING (per db.py's record_action), but choose_strategy must see
        // it as SNOOZED for the purposes of picking the next strategy —
        // mirrors main.py's _build_state().
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDays = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 2, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 3, DayStatus.PENDING, "snoozed", "other", 1, "encouragement")
        );
        when(strandsClient.generate(anyString(), anyMap()))
                .thenReturn(new StrandsResponse("text", "strands"));

        InterventionResult result = coachingService.generateIntervention(habit, 3, allDays);

        // last (synthetic) day is SNOOZED with reason "other", phase for day 3
        // of 21 is getting_started -> reduce_task.
        assertThat(result.strategy()).isEqualTo(InterventionStrategy.REDUCE_TASK);
    }

    // ---- generateActionResponse ----

    @Test
    void actionResponseDoneOnDay1IsCompletionFirstAndUsesStrandsKey() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDaysAfter = List.of(dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null));
        when(strandsClient.generate(anyString(), anyMap()))
                .thenReturn(new StrandsResponse("Nice start!", "strands"));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 1, "done", allDaysAfter);

        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_FIRST);
        assertThat(result.text()).isEqualTo("Nice start!");
        assertThat(result.source()).isEqualTo("strands");
        verify(strandsClient).generate(eq("completion_first"), anyMap());
    }

    @Test
    void actionResponseDoneOnFinalDayIsCompletionFinal() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 3);
        List<HabitDay> allDaysAfter = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 2, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 3, DayStatus.DONE, "done", null, 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 3, "done", allDaysAfter);

        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_FINAL);
    }

    @Test
    void actionResponseDoneAfterPriorMissedDayIsRecovery() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDaysAfter = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 2, DayStatus.MISSED, "missed", "no_time", 0, null),
                dayRow(1L, 3, DayStatus.DONE, "done", null, 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 3, "done", allDaysAfter);

        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_RECOVERY);
    }

    @Test
    void actionResponseDoneOnMilestoneDaySevenIsMilestoneNotPlain() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDaysAfter = new java.util.ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            allDaysAfter.add(dayRow(1L, d, DayStatus.DONE, "done", null, 0, null));
        }
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 7, "done", allDaysAfter);

        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_MILESTONE);
    }

    @Test
    void actionResponseDoneOnOrdinaryDayIsPlain() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDaysAfter = List.of(
                dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null),
                dayRow(1L, 2, DayStatus.DONE, "done", null, 0, null)
        );
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 2, "done", allDaysAfter);

        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_PLAIN);
    }

    @Test
    void actionResponseMissedSingleVsConsecutive() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        List<HabitDay> afterOneMiss = List.of(dayRow(1L, 1, DayStatus.MISSED, "missed", "forgot", 0, null));
        ActionResponseResult single = coachingService.generateActionResponse(habit, 1, "missed", afterOneMiss);
        assertThat(single.kind()).isEqualTo(ResponseKind.MISS_SINGLE);
        assertThat(single.consecutiveMissed()).isEqualTo(1);

        List<HabitDay> afterTwoMisses = List.of(
                dayRow(1L, 1, DayStatus.MISSED, "missed", "forgot", 0, null),
                dayRow(1L, 2, DayStatus.MISSED, "missed", "something_came_up", 0, null)
        );
        ActionResponseResult consecutive = coachingService.generateActionResponse(habit, 2, "missed", afterTwoMisses);
        assertThat(consecutive.kind()).isEqualTo(ResponseKind.MISS_CONSECUTIVE);
        assertThat(consecutive.consecutiveMissed()).isEqualTo(2);
    }

    @Test
    void actionResponseFallsBackToTemplateWhenStrandsUnavailable() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        List<HabitDay> allDaysAfter = List.of(dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null));
        when(strandsClient.generate(anyString(), anyMap()))
                .thenThrow(new StrandsUnavailableException("down", null));

        ActionResponseResult result = coachingService.generateActionResponse(habit, 1, "done", allDaysAfter);

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.kind()).isEqualTo(ResponseKind.COMPLETION_FIRST);
        assertThat(result.text()).isEqualTo(
                "You showed up. That's the hardest part of starting. Day 1 complete — you've officially begun.");
    }

    // ---- AI personalization: profile facts (§2) ----

    @Test
    void generateInterventionOmitsProfileFactsWhenNoProfileSaved() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        when(profileService.getProfile()).thenReturn(Optional.empty());
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        coachingService.generateIntervention(habit, 1, List.of());

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(anyString(), factsCaptor.capture());
        assertThat(factsCaptor.getValue()).doesNotContainKeys("user_name", "user_age", "age_group", "user_gender");
    }

    @Test
    void generateInterventionIncludesProfileFactsWhenProfileSaved() {
        Habit habit = new Habit("Gym", "🏋️", "18:00", 30, 21);
        when(profileService.getProfile()).thenReturn(Optional.of(new UserProfile("Asha", 9, Gender.FEMALE)));
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        coachingService.generateIntervention(habit, 1, List.of());

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(anyString(), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();
        assertThat(facts.get("user_name")).isEqualTo("Asha");
        assertThat(facts.get("user_age")).isEqualTo(9);
        assertThat(facts.get("age_group")).isEqualTo("child");
        assertThat(facts.get("user_gender")).isEqualTo("female");
    }

    @Test
    void generateActionResponseIncludesProfileFactsWhenProfileSaved() {
        Habit habit = new Habit("Walk", "🚶", "08:00", 20, 21);
        when(profileService.getProfile()).thenReturn(Optional.of(new UserProfile("Ravi", 68, Gender.MALE)));
        List<HabitDay> allDaysAfter = List.of(dayRow(1L, 1, DayStatus.DONE, "done", null, 0, null));
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        coachingService.generateActionResponse(habit, 1, "done", allDaysAfter);

        ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(strandsClient).generate(anyString(), factsCaptor.capture());
        Map<String, Object> facts = factsCaptor.getValue();
        assertThat(facts.get("user_name")).isEqualTo("Ravi");
        assertThat(facts.get("age_group")).isEqualTo("older_adult");
        assertThat(facts.get("user_gender")).isEqualTo("male");
    }

    @Test
    void ageGroupBucketsCoverChildTeenAdultAndOlderAdult() {
        Habit habit = new Habit("Study", "📚", "16:00", 30, 21);
        when(strandsClient.generate(anyString(), anyMap())).thenReturn(new StrandsResponse("text", "strands"));

        record Case(int age, String expectedGroup) {
        }
        for (Case c : List.of(new Case(8, "child"), new Case(15, "teen"), new Case(35, "adult"), new Case(70, "older_adult"))) {
            when(profileService.getProfile()).thenReturn(Optional.of(new UserProfile("U", c.age(), Gender.OTHER)));
            coachingService.generateIntervention(habit, 1, List.of());

            ArgumentCaptor<Map<String, Object>> factsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(strandsClient, org.mockito.Mockito.atLeastOnce()).generate(anyString(), factsCaptor.capture());
            assertThat(factsCaptor.getValue().get("age_group")).isEqualTo(c.expectedGroup());
        }
    }
}
