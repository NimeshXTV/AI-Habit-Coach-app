package com.habitcoach.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body for POST /api/profile — onboarding's Name / Age / Gender steps. */
public record ProfileRequest(
        @NotBlank String name,
        @NotNull @Min(1) @Max(120) Integer age,
        @NotNull Gender gender
) {
}
