package com.habitcoach.system;

import com.habitcoach.strands.StrandsClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces Strands-service reachability under GET /actuator/health as a
 * named component ("strands"). Note: Spring Boot Actuator's default status
 * aggregation means the overall /actuator/health status also flips to DOWN
 * when this does — verified empirically. That's an accurate signal (a core
 * dependency really is unreachable), not a bug, but it does mean a health
 * check gate that expects "app is up" would need to look at this component
 * specifically rather than the aggregate status. The app itself keeps
 * serving DB-backed requests fine (see StrandsUnavailableException) — only
 * calls that need Strands are affected.
 */
@Component("strands")
public class StrandsHealthIndicator implements HealthIndicator {

    private final StrandsClient strandsClient;

    public StrandsHealthIndicator(StrandsClient strandsClient) {
        this.strandsClient = strandsClient;
    }

    @Override
    public Health health() {
        if (strandsClient.isHealthy()) {
            return Health.up().build();
        }
        return Health.down().withDetail("reason", "Strands service unreachable").build();
    }
}
