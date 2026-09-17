package com.habitcoach.goal;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parses a goal WITHOUT creating anything, mirroring the reference's
 * POST /api/habits/parse — so the mobile app can check time_specified
 * first and ask the user to confirm a time rather than silently locking
 * in a guessed default.
 */
@RestController
@RequestMapping("/api/habits")
public class GoalController {

    private final GoalParser goalParser;

    public GoalController(GoalParser goalParser) {
        this.goalParser = goalParser;
    }

    @PostMapping("/parse")
    public ParsedGoal parseGoal(@Valid @RequestBody GoalRequest body) {
        return goalParser.parse(body.text());
    }
}
