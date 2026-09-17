package com.habitcoach.habit;

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
 * End-to-end test of the full suggest -> approve reschedule UX flow: the
 * AI (via GET /current's 'reschedule' strategy) only ever SURFACES a
 * suggested_time; the habit's actual time_of_day must stay untouched until
 * an explicit POST /schedule call. This is the core product rule from the
 * spec ("the AI may suggest a new time, but MUST NOT silently change the
 * user's schedule") verified as actual backend behavior, not just a
 * comment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RescheduleFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HabitDayRepository habitDayRepository;

    private long createHabit(String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").contentType("application/json")
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
    void suggestedTimeDoesNotMutateScheduleUntilApproved() throws Exception {
        long id = createHabit("gym every day at 6pm"); // time_of_day = 18:00
        setDay(id, 1, DayStatus.MISSED, "missed", "no_time");
        setDay(id, 2, DayStatus.MISSED, "missed", "no_time");

        String currentBody = mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("reschedule"))
                .andExpect(jsonPath("$.suggested_time").value("19:00"))
                .andReturn().getResponse().getContentAsString();

        // The suggestion must NOT have touched the actual schedule.
        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(jsonPath("$.habit.time_of_day").value("18:00"));

        // Calling /current again (no action taken) still just suggests —
        // still no mutation, proving repeated reads are side-effect-free
        // on the schedule itself.
        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(jsonPath("$.suggested_time").value("19:00"));
        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(jsonPath("$.habit.time_of_day").value("18:00"));

        String suggestedTime = objectMapper.readTree(currentBody).get("suggested_time").asText();

        // Now the user explicitly approves it.
        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"" + suggestedTime + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time_of_day").value(suggestedTime));

        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(jsonPath("$.habit.time_of_day").value(suggestedTime));
    }

    @Test
    void userCanChooseADifferentTimeInsteadOfTheSuggestion() throws Exception {
        long id = createHabit("gym every day at 6pm");
        setDay(id, 1, DayStatus.MISSED, "missed", "too_tired");
        setDay(id, 2, DayStatus.MISSED, "missed", "too_tired");

        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(jsonPath("$.suggested_time").value("19:00"));

        // User picks 08:00 instead of the suggested 19:00 — must be honored as-is.
        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time_of_day").value("08:00"));

        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(jsonPath("$.habit.time_of_day").value("08:00"));
    }

    @Test
    void suggestedTimeIsComputedFromCurrentAuthoritativeTimeNotAStaleValue() throws Exception {
        long id = createHabit("gym every day at 6pm"); // 18:00
        // Move the schedule once, unrelated to any suggestion.
        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                .content("{\"time_of_day\":\"10:00\"}")).andExpect(status().isOk());

        setDay(id, 1, DayStatus.MISSED, "missed", "no_time");
        setDay(id, 2, DayStatus.MISSED, "missed", "no_time");

        // Suggestion must be +1h from the CURRENT (10:00) time, not the
        // original creation-time (18:00).
        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(jsonPath("$.strategy").value("reschedule"))
                .andExpect(jsonPath("$.suggested_time").value("11:00"));
    }

    @Test
    void nonRescheduleStrategyNeverIncludesASuggestedTime() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(jsonPath("$.strategy").value("encouragement"))
                .andExpect(jsonPath("$.suggested_time").doesNotExist());
    }
}
