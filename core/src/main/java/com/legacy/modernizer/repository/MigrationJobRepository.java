package com.legacy.modernizer.repository;

import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {
    List<MigrationJob> findByStatus(JobStatus status);
}
