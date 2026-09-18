package com.habitcoach.habit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for the mobile-referenced lifecycle endpoints that
 * hadn't been ported yet: POST /continue, POST /stop, DELETE /{id}. These
 * are wired into HabitScreen's Day-21 finish card and ChallengesScreen's
 * Delete button, so mobile integration needed them even though earlier
 * slices didn't call them out explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HabitLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long createHabit(String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void continueWithNoBodyCreatesFreshHabitFromSameNameAndTime() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/continue").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)))
                .andExpect(jsonPath("$.name").value("Gym"))
                .andExpect(jsonPath("$.time_of_day").value("18:00"))
                .andExpect(jsonPath("$.total_days").value(21))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void continueUnknownHabitReturns404() throws Exception {
        mockMvc.perform(post("/api/habits/999999/continue").header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopReturnsOkAndHabitDetailReflectsStoppedStatus() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/stop").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(jsonPath("$.habit.status").value("stopped"));
    }

    @Test
    void stoppedHabitCurrentReturnsFinishedSummaryShape() throws Exception {
        long id = createHabit("gym every day at 6pm");
        mockMvc.perform(post("/api/habits/" + id + "/stop").header("X-Device-Id", "device-1")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits/" + id + "/current").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    void deleteRemovesHabitAndSubsequentGetIs404() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(delete("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownHabitReturns404() throws Exception {
        mockMvc.perform(delete("/api/habits/999999").header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    @Test
    void deletingOneHabitLeavesTheOtherFullyIntact() throws Exception {
        long gym = createHabit("gym every day at 6pm");
        long read = createHabit("read every night for 14 days");

        mockMvc.perform(delete("/api/habits/" + gym).header("X-Device-Id", "device-1")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits/" + gym).header("X-Device-Id", "device-1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/habits/" + read).header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habit.id").value(read))
                .andExpect(jsonPath("$.days", org.hamcrest.Matchers.hasSize(14)));

        mockMvc.perform(get("/api/habits").header("X-Device-Id", "device-1"))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(read));
    }
}
