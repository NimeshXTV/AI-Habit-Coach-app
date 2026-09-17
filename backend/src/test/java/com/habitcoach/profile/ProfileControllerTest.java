package com.habitcoach.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end HTTP tests for GET/POST /api/profile — the onboarding contract
 * the mobile app's App.tsx/OnboardingScreen.tsx depend on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository repository;

    @Test
    void getReturns404WhenNoProfileYet() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("profile not found"));
    }

    @Test
    void postCreatesAProfileAndGetThenReturnsIt() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"Asha\",\"age\":29,\"gender\":\"female\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"))
                .andExpect(jsonPath("$.age").value(29))
                .andExpect(jsonPath("$.gender").value("female"));

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"));
    }

    @Test
    void postTwiceUpsertsRatherThanCreatingASecondProfile() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                .content("{\"name\":\"Asha\",\"age\":29,\"gender\":\"female\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"Asha K.\",\"age\":30,\"gender\":\"female\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha K."));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void blankNameReturns400() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"\",\"age\":20,\"gender\":\"other\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ageOutOfRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"Name\",\"age\":0,\"gender\":\"other\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"Name\",\"age\":150,\"gender\":\"other\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingFieldsReturn400() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gendersOthersMapsToOtherOnTheWire() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                        .content("{\"name\":\"Name\",\"age\":40,\"gender\":\"other\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("other"));
    }

    @Test
    void deleteClearsTheProfileAndGetThen404sAgain() throws Exception {
        mockMvc.perform(post("/api/profile").contentType("application/json")
                .content("{\"name\":\"Asha\",\"age\":29,\"gender\":\"female\"}")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/profile")).andExpect(status().isNotFound());
        assertThat(repository.count()).isEqualTo(0);
    }
}
