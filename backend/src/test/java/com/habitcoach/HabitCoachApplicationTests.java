package com.habitcoach;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context load, on an in-memory database (application-test.yml)
 * so the test suite never touches the dev-mode file-based H2 store configured
 * in application.yml.
 */
@SpringBootTest
@ActiveProfiles("test")
class HabitCoachApplicationTests {

    @Test
    void contextLoads() {
    }
}
