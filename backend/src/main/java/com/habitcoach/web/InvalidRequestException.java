package com.habitcoach.web;

/** Maps to HTTP 400 — see ApiExceptionHandler. Mirrors FastAPI's
 * HTTPException(400, ...) usage in the reference implementation (e.g. the
 * time_of_day format check in POST /api/habits/confirm). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
