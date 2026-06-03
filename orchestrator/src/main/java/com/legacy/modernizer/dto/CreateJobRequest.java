package com.legacy.modernizer.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String name,
        @NotBlank String sourceDirectory
) {}
