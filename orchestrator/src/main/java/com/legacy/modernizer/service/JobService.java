package com.legacy.modernizer.service;

import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.statemachine.JobStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Transactional
public class JobService {

    private final MigrationJobRepository jobRepository;

    public JobService(MigrationJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /** Creates a new job in PENDING state. */
    public MigrationJob create(String name, String sourceDirectory) {
        return jobRepository.save(MigrationJob.builder()
                .name(name)
                .sourceDirectory(sourceDirectory)
                .status(JobStatus.PENDING)
                .build());
    }

    /** Moves the job to the next sequential state on the happy path. */
    public MigrationJob advance(Long jobId) {
        MigrationJob job = findById(jobId);
        JobStatus next = JobStateMachine.nextState(job.getStatus());
        JobStateMachine.validate(job.getStatus(), next);
        job.setStatus(next);
        return jobRepository.save(job);
    }

    /** Moves the job to FAILED and records the reason in metadata. */
    public MigrationJob fail(Long jobId, String reason) {
        MigrationJob job = findById(jobId);
        JobStateMachine.validate(job.getStatus(), JobStatus.FAILED);
        job.setStatus(JobStatus.FAILED);
        Map<String, Object> meta = job.getMetadata() != null
                ? new HashMap<>(job.getMetadata()) : new HashMap<>();
        meta.put("failureReason", reason);
        job.setMetadata(meta);
        return jobRepository.save(job);
    }

    /** Resets a FAILED job back to PENDING so it can be re-triggered. */
    public MigrationJob retry(Long jobId) {
        MigrationJob job = findById(jobId);
        JobStateMachine.validate(job.getStatus(), JobStatus.PENDING);
        job.setStatus(JobStatus.PENDING);
        Map<String, Object> meta = job.getMetadata() != null
                ? new HashMap<>(job.getMetadata()) : new HashMap<>();
        meta.remove("failureReason");
        job.setMetadata(meta);
        return jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public MigrationJob findById(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<MigrationJob> findAll() {
        return jobRepository.findAll();
    }
}
