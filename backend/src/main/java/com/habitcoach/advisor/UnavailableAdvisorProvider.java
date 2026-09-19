package com.habitcoach.advisor;

import org.springframework.stereotype.Component;

/**
 * The honest, deterministic fallback: always says plainly that no real
 * answer is available, never calls Strands, Bedrock, Ollama, or
 * CoachModel/FallbackTemplates, and never fabricates or templates a
 * stand-in reply. Used two ways: (1) StrandsAdvisorProvider (the @Primary,
 * actually-registered-for-injection AdvisorProvider — see its javadoc)
 * delegates to this class whenever Strands fails, times out, or returns an
 * unusable response; (2) this class remains a plain @Component in its own
 * right, so AdvisorService's behavior with no working AdvisorProvider at
 * all (e.g. in a context that doesn't wire StrandsAdvisorProvider) is
 * unchanged from before that provider existed.
 */
@Component
public class UnavailableAdvisorProvider implements AdvisorProvider {

    @Override
    public String respond(AdvisorContext context) {
        throw new AdvisorUnavailableException(
                "The Habit Advisor isn't connected to an AI service yet.");
    }
}
