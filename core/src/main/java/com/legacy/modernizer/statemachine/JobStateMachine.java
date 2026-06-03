package com.legacy.modernizer.statemachine;

import com.legacy.modernizer.model.JobStatus;

import java.util.Map;
import java.util.Set;

/**
 * Defines and enforces valid job state transitions.
 *
 * Happy path:   PENDING → ANALYZING → PLANNING → GENERATING → DONE
 * Error path:   any non-terminal state → FAILED
 * Retry path:   FAILED → PENDING
 */
public final class JobStateMachine {

    private static final Map<JobStatus, Set<JobStatus>> TRANSITIONS = Map.of(
            JobStatus.PENDING,    Set.of(JobStatus.ANALYZING,  JobStatus.FAILED),
            JobStatus.ANALYZING,  Set.of(JobStatus.PLANNING,   JobStatus.FAILED),
            JobStatus.PLANNING,   Set.of(JobStatus.GENERATING, JobStatus.FAILED),
            JobStatus.GENERATING, Set.of(JobStatus.DONE,       JobStatus.FAILED),
            JobStatus.DONE,       Set.of(),
            JobStatus.FAILED,     Set.of(JobStatus.PENDING)
    );

    private JobStateMachine() {}

    /**
     * Returns the next sequential state on the happy path.
     * Throws {@link IllegalStateException} if {@code current} is terminal or FAILED.
     */
    public static JobStatus nextState(JobStatus current) {
        return switch (current) {
            case PENDING    -> JobStatus.ANALYZING;
            case ANALYZING  -> JobStatus.PLANNING;
            case PLANNING   -> JobStatus.GENERATING;
            case GENERATING -> JobStatus.DONE;
            default -> throw new IllegalStateException(
                    "Cannot advance from terminal state: " + current);
        };
    }

    /**
     * Validates that the {@code from → to} transition is permitted.
     * Throws {@link IllegalStateException} if the transition is invalid.
     */
    public static void validate(JobStatus from, JobStatus to) {
        Set<JobStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalStateException(
                    "Invalid transition: " + from + " → " + to
                    + ". Allowed: " + allowed);
        }
    }

    /** Returns {@code true} if the job is in a terminal state (DONE or FAILED). */
    public static boolean isTerminal(JobStatus status) {
        return status == JobStatus.DONE || status == JobStatus.FAILED;
    }
}
