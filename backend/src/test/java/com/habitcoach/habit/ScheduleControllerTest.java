package com.habitcoach.habit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for POST /api/habits/{id}/schedule — the only
 * endpoint that changes a habit's time_of_day. Confirms the JSON contract
 * (returns the full updated Habit, same shape as create/confirm) and that
 * GET endpoints reflect the authoritative time afterward.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long createHabit(String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").contentType("application/json")
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void validRescheduleReturnsUpdatedHabit() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"19:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.time_of_day").value("19:30"))
                .andExpect(jsonPath("$.name").value("Gym"));
    }

    @Test
    void authoritativeTimeIsReflectedInSubsequentGets() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                .content("{\"time_of_day\":\"07:00\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habit.time_of_day").value("07:00"));

        mockMvc.perform(get("/api/habits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].time_of_day").value("07:00"));
    }

    @Test
    void malformedTimeReturns400AndLeavesScheduleUnchanged() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"7:30pm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("time_of_day must be 'HH:MM' 24h"));

        mockMvc.perform(get("/api/habits/" + id))
                .andExpect(jsonPath("$.habit.time_of_day").value("18:00"));
    }

    @Test
    void outOfRangeTimeReturns400() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"25:61\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTimeOfDayFieldReturns400() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/schedule").contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownHabitReturns404() throws Exception {
        mockMvc.perform(post("/api/habits/999999/schedule").contentType("application/json")
                        .content("{\"time_of_day\":\"09:00\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    @Test
    void twoHabitsAreScheduledIndependently() throws Exception {
        long gym = createHabit("gym every day at 6pm");
        long read = createHabit("read every night for 14 days");

        mockMvc.perform(post("/api/habits/" + gym + "/schedule").contentType("application/json")
                .content("{\"time_of_day\":\"05:30\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits/" + gym))
                .andExpect(jsonPath("$.habit.time_of_day").value("05:30"));
        mockMvc.perform(get("/api/habits/" + read))
                .andExpect(jsonPath("$.habit.time_of_day").value("21:00"));
    }
}
