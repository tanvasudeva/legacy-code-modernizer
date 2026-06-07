package com.legacy.modernizer.dto;

import com.legacy.modernizer.model.MigrationJob;

import java.time.LocalDateTime;

public record JobStatusResponse(
        Long id,
        String name,
        String status,
        String sourceDirectory,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static JobStatusResponse from(MigrationJob job) {
        return new JobStatusResponse(
                job.getId(),
                job.getName(),
                job.getStatus().name(),
                job.getSourceDirectory(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}

