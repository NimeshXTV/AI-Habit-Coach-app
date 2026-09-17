package com.habitcoach.coaching;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.habitcoach.habit.Habit;

/**
 * Mirrors the two response shapes GET /api/habits/{id}/current returns in
 * the reference implementation. @JsonInclude(NON_NULL) omits fields the
 * reference's dict literal simply never included, rather than serializing
 * them as null (e.g. a finished response has no intervention_text key at
 * all) — matching the mobile app's src/types.ts CurrentIntervention, whose
 * fields are all optional for exactly this reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentInterventionResponse(
        Habit habit,
        int dayNumber,
        Integer totalDays,
        String interventionText,
        String strategy,
        String generatedBy,
        String suggestedTime,
        boolean finished,
        String summary,
        String summaryGeneratedBy
) {

    public static CurrentInterventionResponse active(Habit habit, int dayNumber, int totalDays,
                                                       String interventionText, String strategy,
                                                       String generatedBy, String suggestedTime) {
        return new CurrentInterventionResponse(habit, dayNumber, totalDays, interventionText, strategy,
                generatedBy, suggestedTime, false, null, null);
    }

    public static CurrentInterventionResponse finished(Habit habit, int dayNumber, String summary,
                                                         String summaryGeneratedBy) {
        return new CurrentInterventionResponse(habit, dayNumber, null, null, null, null, null,
                true, summary, summaryGeneratedBy);
    }
}
