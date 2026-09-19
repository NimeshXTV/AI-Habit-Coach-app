package com.habitcoach.advisor;

/**
 * The seam a real LLM connects through. AdvisorService depends only on this
 * interface, never on Strands or Bedrock directly. The registered
 * implementation is StrandsAdvisorProvider (Spring Boot -> StrandsClient ->
 * Strands service -> Bedrock Mantle), marked @Primary so it's the one
 * AdvisorService's constructor injection picks up; it falls back to
 * UnavailableAdvisorProvider (still a plain @Component, unchanged) whenever
 * Strands fails, times out, or returns an unusable response — see
 * StrandsAdvisorProvider's javadoc. None of this required any change to
 * AdvisorService, AdvisorController, the HTTP contract, or the mobile chat
 * UI.
 */
public interface AdvisorProvider {

    /**
     * Returns the advisor's reply text, or throws AdvisorUnavailableException
     * if no real answer can be produced right now (no provider configured,
     * the underlying AI service is unreachable, etc.) — never a fabricated
     * or templated stand-in answer.
     */
    String respond(AdvisorContext context);
}
