package com.habitcoach.advisor;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Habit Advisor chat endpoint. Device-scoped and habit-scoped exactly like
 * every route in HabitController (X-Device-Id header, 404 on an unowned/
 * unknown habit) — see AdvisorService.sendMessage. Today this always
 * returns an explicit "unavailable" response (see UnavailableAdvisorProvider);
 * that is the honest current contract, not a placeholder that pretends to
 * answer.
 */
@RestController
@RequestMapping("/api/habits/{habitId}/advisor")
public class AdvisorController {

    private final AdvisorService advisorService;

    public AdvisorController(AdvisorService advisorService) {
        this.advisorService = advisorService;
    }

    @PostMapping("/message")
    public AdvisorMessageResponse sendMessage(@RequestHeader("X-Device-Id") String deviceId,
                                               @PathVariable Long habitId,
                                               @Valid @RequestBody AdvisorMessageRequest body) {
        return advisorService.sendMessage(deviceId, habitId, body);
    }
}
