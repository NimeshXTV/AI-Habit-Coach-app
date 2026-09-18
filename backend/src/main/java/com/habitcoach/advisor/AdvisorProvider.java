package com.habitcoach.advisor;

/**
 * The seam a real LLM connects through later. AdvisorService depends only
 * on this interface, never on Strands or Bedrock directly — so the future
 * change is exactly "add a StrandsAdvisorProvider implementation (Spring
 * Boot -> Strands -> Bedrock/LLM) and make it the active @Component instead
 * of UnavailableAdvisorProvider," with zero change to AdvisorService,
 * AdvisorController, the HTTP contract, or the mobile chat UI.
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
