package com.habitcoach.habit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitcoach.journey.DayStatus;
import com.habitcoach.journey.HabitDay;
import com.habitcoach.journey.HabitDayRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for GET /api/habits/{id}/current. The test
 * profile points `strands.base-url` at an unreachable address (see
 * application-test.yml), so every call here deterministically exercises
 * the Java-side fallback-template path — that's itself a real assertion
 * about fallback behavior, not just a test-environment quirk. Successful
 * real Strands generation is covered by StrandsClientTest (mocked HTTP)
 * and by the manual live-backend verification.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CurrentInterventionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HabitDayRepository habitDayRepository;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private long createHabit(String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void setDay(long habitId, int dayNumber, DayStatus status, String action, String feedbackReason) {
        HabitDay day = habitDayRepository.findByHabitIdAndDayNumber(habitId, dayNumber).orElseThrow();
        day.setStatus(status);
        day.setAction(action);
        day.setFeedbackReason(feedbackReason);
        habitDayRepository.save(day);
    }

    @Test
    void freshHabitDay1IsEncouragementViaFallback() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habit.id").value(id))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.total_days").value(21))
                .andExpect(jsonPath("$.strategy").value("encouragement"))
                .andExpect(jsonPath("$.generated_by").value("template"))
                .andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.intervention_text").isNotEmpty())
                .andExpect(jsonPath("$.suggested_time").doesNotExist());
    }

    @Test
    void currentEndpointRecordsInterventionOnTheDayRow() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1")).andExpect(status().isOk());

        HabitDay day1 = habitDayRepository.findByHabitIdAndDayNumber(id, 1).orElseThrow();
        assertThat(day1.getInterventionText()).isNotBlank();
        assertThat(day1.getInterventionStrategy()).isEqualTo("encouragement");
    }

    @Test
    void dayBeyondOneReflectsRecordedHistory() throws Exception {
        long id = createHabit("gym every day at 6pm");
        setDay(id, 1, DayStatus.DONE, "done", null);

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(2))
                .andExpect(jsonPath("$.strategy").value("encouragement"));
    }

    @Test
    void repeatedNoTimeReasonSurfacesRescheduleWithSuggestedTime() throws Exception {
        long id = createHabit("gym every day at 6pm");
        setDay(id, 1, DayStatus.MISSED, "missed", "no_time");
        setDay(id, 2, DayStatus.MISSED, "missed", "no_time");

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(3))
                .andExpect(jsonPath("$.strategy").value("reschedule"))
                .andExpect(jsonPath("$.suggested_time").value("19:00"));
    }

    @Test
    void twoConsecutiveMissesSurfaceReflection() throws Exception {
        long id = createHabit("gym every day at 6pm");
        setDay(id, 1, DayStatus.MISSED, "missed", "forgot");
        setDay(id, 2, DayStatus.MISSED, "missed", "something_came_up");

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("reflection"));
    }

    @Test
    void snoozedTodayInfluencesTheSameDaysNextStrategy() throws Exception {
        long id = createHabit("gym every day at 6pm");
        // Day 1 snoozed but NOT finalized (record_action keeps status
        // pending for snoozed) -> current day number stays 1.
        setDay(id, 1, DayStatus.PENDING, "snoozed", "not_feeling_it");

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.strategy").value("reduce_task"));
    }

    @Test
    void milestoneDaySevenIsReinforcement() throws Exception {
        long id = createHabit("gym every day at 6pm");
        for (int d = 1; d <= 6; d++) {
            setDay(id, d, DayStatus.DONE, "done", null);
        }

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(7))
                .andExpect(jsonPath("$.strategy").value("reinforcement"));
    }

    @Test
    void twoHabitsProduceIndependentCurrentDayAndStrategy() throws Exception {
        long gym = createHabit("gym every day at 6pm");
        long read = createHabit("read every night for 14 days");

        setDay(gym, 1, DayStatus.MISSED, "missed", "no_time");
        setDay(gym, 2, DayStatus.MISSED, "missed", "no_time");
        setDay(read, 1, DayStatus.DONE, "done", null);

        mockMvc.perform(get("/api/habits/" + gym + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(3))
                .andExpect(jsonPath("$.strategy").value("reschedule"));

        mockMvc.perform(get("/api/habits/" + read + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(2))
                .andExpect(jsonPath("$.strategy").value("encouragement"))
                .andExpect(jsonPath("$.total_days").value(14));
    }

    @Test
    void nonActiveHabitReturnsFinishedSummaryShapeInstead() throws Exception {
        long id = createHabit("gym every day at 6pm");
        setDay(id, 1, DayStatus.DONE, "done", null);

        Habit habit = habitRepository.findById(id).orElseThrow();
        habit.setStatus(HabitStatus.COMPLETED);
        habitRepository.save(habit);

        String body = mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.summary_generated_by").value("template"))
                .andExpect(jsonPath("$.intervention_text").doesNotExist())
                .andExpect(jsonPath("$.strategy").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("summary").asText()).contains("1 out of 21");
    }

    @Test
    void unknownHabitReturns404() throws Exception {
        mockMvc.perform(get("/api/habits/999999/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound());
    }
}
