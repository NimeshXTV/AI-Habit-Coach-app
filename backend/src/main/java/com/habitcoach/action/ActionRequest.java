package com.habitcoach.action;

import jakarta.validation.constraints.NotNull;

/**
 * Mirrors the reference's ActionIn. `action` must be present (matching
 * pydantic's required `action: str`); its actual value ("done"/"snoozed"/
 * "missed") is validated in ActionService, not here, so the exact
 * reference error message ("action must be done | snoozed | missed") can
 * be reproduced rather than a generic Bean Validation message.
 * feedback_reason/feedback_note are both optional, matching the reference.
 */
public record ActionRequest(@NotNull String action, String feedbackReason, String feedbackNote) {
}
