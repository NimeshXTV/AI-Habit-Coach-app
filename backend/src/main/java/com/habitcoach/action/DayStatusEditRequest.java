package com.habitcoach.action;

import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/habits/{id}/days/{dayNumber}/status — a manual
 * correction to a single day's status (calendar tap-to-edit), separate
 * from the done/snoozed/missed action flow in ActionRequest. `status`
 * presence is enforced here (matching ActionRequest's @NotNull
 * convention); its actual value ("pending"/"done"/"missed") is validated
 * in ActionService.editDayStatus, not here, so the exact error message can
 * be controlled there the same way action validation already is.
 */
public record DayStatusEditRequest(@NotNull String status) {
}
