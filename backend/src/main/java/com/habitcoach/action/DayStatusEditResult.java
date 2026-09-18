package com.habitcoach.action;

/**
 * Response of POST /api/habits/{id}/days/{dayNumber}/status. Deliberately
 * has NO responseText/responseKind/generatedBy fields (unlike ActionResult)
 * — the shape itself is proof this path never calls CoachingService/
 * Strands: a manual calendar correction is a data fix, not a coaching
 * moment.
 */
public record DayStatusEditResult(boolean ok, int dayNumber, String status) {
}
