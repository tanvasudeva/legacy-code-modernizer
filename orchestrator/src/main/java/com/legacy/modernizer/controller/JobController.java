package com.legacy.modernizer.controller;

import com.legacy.modernizer.dto.CreateJobRequest;
import com.legacy.modernizer.dto.JobStatusResponse;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /** Create a new migration job in PENDING state. */
    @PostMapping
    public ResponseEntity<JobStatusResponse> create(@Valid @RequestBody CreateJobRequest request) {
        MigrationJob job = jobService.create(request.name(), request.sourceDirectory());
        return ResponseEntity.status(HttpStatus.CREATED).body(JobStatusResponse.from(job));
    }

    /** Get the current status and metadata of a job. */
    @GetMapping("/{id}/status")
    public ResponseEntity<JobStatusResponse> getStatus(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(JobStatusResponse.from(jobService.findById(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** List all jobs. */
    @GetMapping
    public ResponseEntity<List<JobStatusResponse>> listAll() {
        return ResponseEntity.ok(
                jobService.findAll().stream().map(JobStatusResponse::from).toList());
    }

    /** Manually advance a job to the next state (for testing / admin use). */
    @PostMapping("/{id}/advance")
    public ResponseEntity<?> advance(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(JobStatusResponse.from(jobService.advance(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** Retry a FAILED job by resetting it to PENDING. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(JobStatusResponse.from(jobService.retry(id)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
