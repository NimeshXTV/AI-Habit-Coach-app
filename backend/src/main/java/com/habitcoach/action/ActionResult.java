package com.habitcoach.action;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mirrors main.py's POST /action response: {"ok", "day_number"} always,
 * plus response_text/response_kind/generated_by/consecutive_missed_days
 * ONLY for done/missed — a "snoozed" action gets just the bare shape
 * (mobile immediately re-fetches GET /current for the adapted nudge
 * instead). @JsonInclude(NON_NULL) omits the extra fields entirely for
 * snoozed, rather than serializing them as null.
 *
 * treeHealth/treeStage/treeComeback are the immediate tree-gamification
 * result of this action (see TreeHealth) — populated for done/missed only,
 * same reasoning as the response fields: a "snoozed" action never touches
 * the tree (see ActionService), so there is nothing new to report.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResult(
        boolean ok,
        int dayNumber,
        String responseText,
        String responseKind,
        String generatedBy,
        Integer consecutiveMissedDays,
        Integer treeHealth,
        String treeStage,
        Boolean treeComeback
) {

    public static ActionResult simple(int dayNumber) {
        return new ActionResult(true, dayNumber, null, null, null, null, null, null, null);
    }

    public static ActionResult withResponse(int dayNumber, String responseText, String responseKind,
                                             String generatedBy, int consecutiveMissedDays,
                                             int treeHealth, String treeStage, boolean treeComeback) {
        return new ActionResult(true, dayNumber, responseText, responseKind, generatedBy, consecutiveMissedDays,
                treeHealth, treeStage, treeComeback);
    }
}
