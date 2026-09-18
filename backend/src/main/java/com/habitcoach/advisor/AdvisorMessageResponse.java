package com.habitcoach.advisor;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body of POST /api/habits/{habitId}/advisor/message. Deliberately
 * explicit about availability rather than ever returning a fabricated or
 * templated reply: `available=false` + `unavailableReason` is the ONLY
 * shape this backend can return today (see UnavailableAdvisorProvider) —
 * `available=true` + `replyText` is the shape a real provider (Strands ->
 * Bedrock/LLM) will return once one is wired in, with no change needed to
 * this DTO or to the mobile client's handling of it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdvisorMessageResponse(boolean available, String replyText, String unavailableReason) {

    public static AdvisorMessageResponse unavailable(String reason) {
        return new AdvisorMessageResponse(false, null, reason);
    }

    public static AdvisorMessageResponse reply(String text) {
        return new AdvisorMessageResponse(true, text, null);
    }
}
