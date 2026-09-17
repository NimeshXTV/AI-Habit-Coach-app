package com.habitcoach.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The reference FastAPI backend and the mobile app's src/types.ts /
 * src/api.ts both speak snake_case JSON (time_of_day, duration_minutes,
 * feedback_reason, ...). Jackson's default would serialize Java's
 * camelCase fields as camelCase and silently break every mobile screen
 * that reads those fields. This customizer is what lets Java entities/DTOs
 * keep normal Java naming while the wire format stays snake_case, so the
 * mobile app needs zero changes for this reason.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseJacksonCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
