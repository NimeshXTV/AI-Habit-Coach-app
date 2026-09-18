package com.habitcoach.advisor;

/**
 * Thrown by an AdvisorProvider when it cannot produce a real reply — today
 * that is always (see UnavailableAdvisorProvider, the only registered
 * provider), since no LLM is wired in yet. Mirrors
 * strands.StrandsUnavailableException's role: callers (AdvisorService)
 * catch this and map it to an explicit AdvisorMessageResponse.unavailable(...)
 * rather than ever falling back to a template or a fabricated reply.
 */
public class AdvisorUnavailableException extends RuntimeException {

    public AdvisorUnavailableException(String message) {
        super(message);
    }

    public AdvisorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
