package com.habitcoach.goal;

/**
 * Java mirror of legacy-reference/backend/goal_parser.py's ParsedGoal
 * dataclass. Field names are camelCase here but serialize as snake_case
 * over the wire (see com.habitcoach.config.JacksonConfig), so POST
 * /api/habits/parse returns exactly the same JSON shape the mobile app
 * already expects.
 */
public record ParsedGoal(
        String name,
        String emoji,
        String timeOfDay,
        int durationMinutes,
        int totalDays,
        boolean timeSpecified
) {
}
