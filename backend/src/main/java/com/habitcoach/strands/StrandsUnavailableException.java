package com.habitcoach.strands;

/**
 * Thrown when the Python Strands service cannot be reached or errors out.
 * This is a failure mode that did not exist in the reference implementation
 * (there, agent.py was called in-process — see legacy-reference/backend/
 * intervention_engine.py's try/except around generate_with_strands()).
 * Callers in the coaching layer should catch this and fall back to a
 * template-rendered response, exactly as the reference implementation did.
 */
public class StrandsUnavailableException extends RuntimeException {

    public StrandsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
