package com.habitcoach.system;

import com.habitcoach.strands.StrandsClient;
import com.habitcoach.strands.StrandsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Scaffold-stage only: a manual/automated way to prove Spring Boot can
 * reach the Python Strands service and get a real generated line back,
 * before the coaching feature that will actually call this in production
 * exists. Safe to remove once CoachingService takes over this call site.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final StrandsClient strandsClient;

    public DiagnosticsController(StrandsClient strandsClient) {
        this.strandsClient = strandsClient;
    }

    @GetMapping("/strands-check")
    public StrandsResponse strandsCheck() {
        Map<String, Object> facts = Map.ofEntries(
                entry("name", "gym"),
                entry("day", 1),
                entry("total", 21),
                entry("completed", 0),
                entry("minutes", 30),
                entry("time_of_day", "6 PM"),
                entry("reason", "trouble with the timing"),
                entry("current_streak", 0),
                entry("consecutive_missed", 0),
                entry("snooze_count_today", 0),
                entry("last_strategy", "none")
        );
        return strandsClient.generate("encouragement", facts);
    }
}
