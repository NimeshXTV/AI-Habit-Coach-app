package com.habitcoach.web;

/** Maps to HTTP 404 — see ApiExceptionHandler. Mirrors FastAPI's
 * HTTPException(404, ...) usage in the reference implementation. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
