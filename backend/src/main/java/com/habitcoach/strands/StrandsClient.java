package com.habitcoach.strands;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Java-side counterpart to legacy-reference/backend/agent.py's
 * generate_with_strands(): turns {key, facts} into coaching text. The
 * difference is this now goes over HTTP to a separate Python process
 * instead of an in-process function call, so it can fail in new ways
 * (connection refused, timeout) that callers must handle explicitly.
 *
 * Takes the auto-configured RestClient.Builder (rather than building its
 * own from scratch) so tests can bind a MockRestServiceServer to the same
 * builder before it reaches this constructor.
 */
@Component
public class StrandsClient {

    private final RestClient restClient;

    public StrandsClient(RestClient.Builder restClientBuilder, StrandsProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Calls POST /generate. Throws StrandsUnavailableException on any
     * network failure, timeout, or non-2xx response — callers are expected
     * to catch this and fall back to a template-rendered line, mirroring
     * the reference implementation's try/except around the in-process call.
     */
    public StrandsResponse generate(String key, Map<String, Object> facts) {
        try {
            StrandsResponse response = restClient.post()
                    .uri("/generate")
                    .body(new StrandsRequest(key, facts))
                    .retrieve()
                    .body(StrandsResponse.class);
            if (response == null) {
                throw new StrandsUnavailableException("Strands service returned an empty body", null);
            }
            return response;
        } catch (RestClientException e) {
            throw new StrandsUnavailableException("Strands service call failed: " + e.getMessage(), e);
        }
    }

    /** Backs the custom health indicator — see system/StrandsHealthIndicator. */
    public boolean isHealthy() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }
}
