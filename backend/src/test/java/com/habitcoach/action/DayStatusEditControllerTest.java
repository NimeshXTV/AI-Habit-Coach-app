package com.habitcoach.action;

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
 * End-to-end HTTP tests for POST /api/habits/{id}/days/{dayNumber}/status —
 * the manual calendar-tap correction endpoint, separate from POST
 * /{id}/action. Confirms the JSON contract, device-ownership 404s (same
 * mechanism as every other habit route), and the validation 400s with
 * their exact messages (see ActionService.editDayStatus).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DayStatusEditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long createHabit(String deviceId, String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", deviceId).contentType("application/json")
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void editingTheCurrentDayReturnsTheUpdatedStatusContract() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.status").value("done"));

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(jsonPath("$.days[0].status").value("done"));
    }

    @Test
    void doneThenMissedThenPendingRoundTripsCorrectly() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                .contentType("application/json").content("{\"status\":\"done\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                .contentType("application/json").content("{\"status\":\"missed\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json").content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(jsonPath("$.days[0].status").value("pending"));
    }

    @Test
    void invalidStatusReturns400WithExactMessage() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"snoozed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("status must be pending | done | missed"));
    }

    @Test
    void missingStatusFieldReturns400() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void outOfRangeDayNumberReturns400WithExactMessage() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/0/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("day_number out of range"));

        mockMvc.perform(post("/api/habits/" + id + "/days/22/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("day_number out of range"));
    }

    @Test
    void aFutureDayNotYetReachedReturns400WithExactMessage() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/2/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("cannot edit a day that hasn't been reached yet"));
    }

    @Test
    void editingANonActiveHabitReturns400() throws Exception {
        long id = createHabit("device-1", "read every night for 1 days");
        mockMvc.perform(post("/api/habits/" + id + "/action").header("X-Device-Id", "device-1")
                .contentType("application/json").content("{\"action\":\"done\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"missed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("only an active challenge's days can be edited"));
    }

    @Test
    void unknownHabitReturns404() throws Exception {
        mockMvc.perform(post("/api/habits/999999/days/1/status").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    @Test
    void aDifferentDeviceCannotEditAnotherDevicesHabitDay() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status").header("X-Device-Id", "a-completely-different-device")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestsWithoutADeviceIdHeaderAreRejected() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/days/1/status")
                        .contentType("application/json")
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest());
    }
}
