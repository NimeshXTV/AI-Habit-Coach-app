package com.habitcoach.habit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for the habit-creation slice, verifying the actual
 * JSON contract (snake_case field names) the mobile app depends on — not
 * just that the Java objects are correct internally. @Transactional rolls
 * back all DB writes after each test so tests don't interfere with each
 * other's habit counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HabitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void parseReturnsSnakeCaseFieldsWithoutPersisting() throws Exception {
        mockMvc.perform(post("/api/habits/parse").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"text\":\"go to the gym every day at 6pm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Go to the gym"))
                .andExpect(jsonPath("$.time_of_day").value("18:00"))
                .andExpect(jsonPath("$.duration_minutes").value(30))
                .andExpect(jsonPath("$.total_days").value(21))
                .andExpect(jsonPath("$.time_specified").value(true));

        // parse must not create anything
        mockMvc.perform(get("/api/habits").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void createPersistsHabitAndReturnsSnakeCaseBody() throws Exception {
        mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"text\":\"read every night for 14 days\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Read"))
                .andExpect(jsonPath("$.time_of_day").value("21:00"))
                .andExpect(jsonPath("$.total_days").value(14))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void confirmUsesUserPickedTimeOverParsedGuess() throws Exception {
        mockMvc.perform(post("/api/habits/confirm").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"text\":\"drink water every day\",\"time_of_day\":\"09:15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Drink water"))
                .andExpect(jsonPath("$.time_of_day").value("09:15"));
    }

    @Test
    void confirmRejectsMalformedTimeWith400() throws Exception {
        mockMvc.perform(post("/api/habits/confirm").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"text\":\"drink water every day\",\"time_of_day\":\"9:15am\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("time_of_day must be 'HH:MM' 24h"));
    }

    @Test
    void createMissingTextFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsAllCreatedHabits() throws Exception {
        mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                .content("{\"text\":\"gym every day at 6pm\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                .content("{\"text\":\"read every night for 14 days\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    void getByIdReturnsHabitAndTwentyOneDays() throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                        .content("{\"text\":\"gym every day at 6pm\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long id = extractId(body);

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habit.id").value(id))
                .andExpect(jsonPath("$.habit.time_of_day").value("18:00"))
                .andExpect(jsonPath("$.days", hasSize(21)))
                .andExpect(jsonPath("$.days[0].day_number").value(1))
                .andExpect(jsonPath("$.days[0].status").value("pending"));
    }

    @Test
    void getByUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/habits/999999").header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    // ---- device isolation (see CLAUDE_CONTEXT.md's onboarding/data-isolation fix) ----

    @Test
    void requestsWithoutADeviceIdHeaderAreRejected() throws Exception {
        mockMvc.perform(get("/api/habits"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDifferentDeviceNeverSeesAnotherDevicesHabitList() throws Exception {
        mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                .content("{\"text\":\"gym every day at 6pm\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/habits").header("X-Device-Id", "a-completely-different-device"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aDifferentDeviceCannotFetchAnotherDevicesHabitById() throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", "device-1").contentType("application/json")
                        .content("{\"text\":\"gym every day at 6pm\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        mockMvc.perform(get("/api/habits/" + id).header("X-Device-Id", "a-completely-different-device"))
                .andExpect(status().isNotFound());
    }

    private static long extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new IllegalStateException("no id in response: " + json);
    }
}
