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
 * End-to-end HTTP tests for POST /api/habits/{id}/action. Like
 * CurrentInterventionControllerTest, the test profile points
 * strands.base-url at an unreachable address, so every response here goes
 * through the deterministic Java fallback templates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActionControllerTest {

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
    void doneReturnsCompletionResponseShape() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.response_text").isNotEmpty())
                .andExpect(jsonPath("$.response_kind").value("completion_first"))
                .andExpect(jsonPath("$.generated_by").value("template"))
                .andExpect(jsonPath("$.consecutive_missed_days").value(0));
    }

    @Test
    void snoozedReturnsBareShapeWithNoResponseFields() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"snoozed\",\"feedback_reason\":\"not_feeling_it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.response_text").doesNotExist())
                .andExpect(jsonPath("$.response_kind").doesNotExist())
                .andExpect(jsonPath("$.generated_by").doesNotExist())
                .andExpect(jsonPath("$.consecutive_missed_days").doesNotExist());
    }

    @Test
    void snoozeThenCurrentShowsShrunkTaskViaEscalatingSnoozeCount() throws Exception {
        long id = createHabit("gym every day at 6pm"); // 30-minute default duration

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                .content("{\"action\":\"snoozed\",\"feedback_reason\":\"not_feeling_it\"}")).andExpect(status().isOk());

        // 1st snooze: attempts=1 -> shrink = max(5, 30/(2+1)) = 10
        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("reduce_task"))
                .andExpect(jsonPath("$.intervention_text").value(org.hamcrest.Matchers.containsString("10 minutes")));

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                .content("{\"action\":\"snoozed\",\"feedback_reason\":\"not_feeling_it\"}")).andExpect(status().isOk());

        // 2nd snooze same day: attempts=2 -> shrink = max(5, 30/(2+2)) = 7
        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("reduce_task"))
                .andExpect(jsonPath("$.intervention_text").value(org.hamcrest.Matchers.containsString("7 minutes")));
    }

    @Test
    void missedRecordsFeedbackAndReturnsMissSingle() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"missed\",\"feedback_reason\":\"too_tired\",\"feedback_note\":\"long day\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_kind").value("miss_single"))
                .andExpect(jsonPath("$.consecutive_missed_days").value(1));
    }

    @Test
    void twoConsecutiveMissesReturnMissConsecutiveOnSecond() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                .content("{\"action\":\"missed\",\"feedback_reason\":\"forgot\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"missed\",\"feedback_reason\":\"something_came_up\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_kind").value("miss_consecutive"))
                .andExpect(jsonPath("$.consecutive_missed_days").value(2));
    }

    @Test
    void invalidActionReturns400() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"finished\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("action must be done | snoozed | missed"));
    }

    @Test
    void invalidFeedbackReasonReturns400() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"missed\",\"feedback_reason\":\"excuses\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "feedback_reason must be one of ['too_tired', 'no_time', 'forgot', 'something_came_up', 'not_feeling_it', 'other']"));
    }

    @Test
    void missingActionFieldReturns400() throws Exception {
        long id = createHabit("gym every day at 6pm");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownHabitReturns404() throws Exception {
        mockMvc.perform(post("/api/habits/999999/action").contentType("application/json")
                        .content("{\"action\":\"done\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("habit not found"));
    }

    @Test
    void doneOnFinalDayCompletesHabitAndSubsequentCurrentReturnsFinishedSummary() throws Exception {
        long id = createHabit("read every night for 2 days");

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                .content("{\"action\":\"done\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/habits/" + id + "/action").contentType("application/json")
                        .content("{\"action\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_kind").value("completion_final"));

        mockMvc.perform(get("/api/habits/" + id + "/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    void twoHabitsActOnIndependentDays() throws Exception {
        long gym = createHabit("gym every day at 6pm");
        long read = createHabit("read every night for 14 days");

        mockMvc.perform(post("/api/habits/" + gym + "/action").contentType("application/json")
                        .content("{\"action\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(1));

        mockMvc.perform(post("/api/habits/" + read + "/action").contentType("application/json")
                        .content("{\"action\":\"missed\",\"feedback_reason\":\"no_time\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day_number").value(1))
                .andExpect(jsonPath("$.response_kind").value("miss_single"));

        mockMvc.perform(get("/api/habits/" + gym + "/current"))
                .andExpect(jsonPath("$.day_number").value(2));
        mockMvc.perform(get("/api/habits/" + read + "/current"))
                .andExpect(jsonPath("$.day_number").value(2));
    }
}
