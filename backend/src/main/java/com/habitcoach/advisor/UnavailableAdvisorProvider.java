package com.habitcoach.advisor;

import org.springframework.stereotype.Component;

/**
 * The only AdvisorProvider registered today. Deliberately never calls
 * Strands, Bedrock, Ollama, or CoachModel/FallbackTemplates — the Habit
 * Advisor is online-only and must clearly say so rather than pretend to
 * answer (see the mobile chat UI's unavailable state). Replace this
 * @Component with a real provider (see AdvisorProvider's javadoc) when an
 * LLM is actually wired in; nothing else in the advisor package needs to
 * change for that swap.
 */
@Component
public class UnavailableAdvisorProvider implements AdvisorProvider {

    @Override
    public String respond(AdvisorContext context) {
        throw new AdvisorUnavailableException(
                "The Habit Advisor isn't connected to an AI service yet.");
    }
}
