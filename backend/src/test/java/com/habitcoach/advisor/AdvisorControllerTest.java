package com.habitcoach.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for POST /api/habits/{id}/advisor/message, run
 * against the app's real (unmodified) Spring context — so this exercises
 * the actual registered @Primary AdvisorProvider bean (StrandsAdvisorProvider),
 * not a mock. application-test.yml points strands.base-url at a
 * deliberately unreachable address, so every call here falls through to
 * UnavailableAdvisorProvider exactly as before StrandsAdvisorProvider
 * existed — deterministic, no live Strands/Bedrock process required.
 * Confirms: the JSON contract, device-ownership 404s (same mechanism as
 * every other habit route), blank-message 400, and — the core requirement
 * for this stage — that no fabricated/template reply is ever returned when
 * the real provider can't be reached. See StrandsAdvisorProviderTest for
 * the successful-Strands-response path (mocked StrandsClient, no HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdvisorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdvisorProvider advisorProvider;

    /** Proves @Primary actually resolved to the real provider for this
     * injection point (not just that the HTTP response happens to look the
     * same either way, since both StrandsAdvisorProvider-falling-back and a
     * bare UnavailableAdvisorProvider produce an identical available=false
     * body). */
    @Test
    void primaryAdvisorProviderIsTheStrandsBackedOne() {
        assertThat(advisorProvider).isInstanceOf(StrandsAdvisorProvider.class);
    }

    private long createHabit(String deviceId, String text) throws Exception {
        String body = mockMvc.perform(post("/api/habits").header("X-Device-Id", deviceId).contentType("application/json")
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void messageReturnsAnExplicitUnavailableResponseNeverAFakeReply() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"message\":\"How am I doing?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reply_text").doesNotExist())
                .andExpect(jsonPath("$.unavailable_reason").isNotEmpty());
    }

    @Test
    void messageWithConversationHistoryIsAccepted() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        String body = "{\"message\":\"What about tomorrow?\",\"history\":["
                + "{\"role\":\"user\",\"text\":\"I missed today\"},"
                + "{\"role\":\"advisor\",\"text\":\"That's okay, let's plan for tomorrow.\"}"
                + "]}";

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void blankMessageReturns400() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingMessageFieldReturns400() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestsWithoutADeviceIdHeaderAreRejected() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message")
                        .contentType("application/json")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownHabitReturns404() throws Exception {
        mockMvc.perform(post("/api/habits/999999/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    // ---- device isolation (mirrors HabitControllerTest's device-isolation coverage) ----

    @Test
    void aDifferentDeviceCannotMessageAnotherDevicesHabitAdvisor() throws Exception {
        long id = createHabit("device-1", "gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/advisor/message").header("X-Device-Id", "a-completely-different-device")
                        .contentType("application/json")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- per-habit isolation: each habit's advisor route is independently
    // ownership-checked, matching the per-habitId scoping the mobile app's
    // local conversation store mirrors (see advisorStore.ts) ----

    @Test
    void twoHabitsForTheSameDeviceAreEachIndependentlyReachable() throws Exception {
        long gym = createHabit("device-1", "gym every day at 6pm");
        long study = createHabit("device-1", "study every day at 8pm");

        mockMvc.perform(post("/api/habits/" + gym + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"message\":\"gym question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(post("/api/habits/" + study + "/advisor/message").header("X-Device-Id", "device-1")
                        .contentType("application/json")
                        .content("{\"message\":\"study question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }
}
