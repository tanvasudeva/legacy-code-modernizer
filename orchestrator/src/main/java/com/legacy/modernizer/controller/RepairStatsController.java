package com.legacy.modernizer.controller;

import com.legacy.modernizer.agent.RepairStats;
import com.legacy.modernizer.eval.EvaluatorService;
import com.legacy.modernizer.eval.model.EvalMetric;
import com.legacy.modernizer.eval.model.MetricName;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.repository.AgentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Phase 4.7 — Compilation repair statistics endpoint.
 *
 * <pre>
 * GET /api/jobs/{id}/repair-stats   — RepairStats JSON for one job
 * </pre>
 *
 * <p>Aggregates data from two sources:
 * <ul>
 *   <li>{@code agent_tasks} (repair_attempts, first_attempt_compiled) — per-service repair tracking</li>
 *   <li>{@code eval_metrics} COMPILATION_POST_REPAIR — final compilation rate from the evaluator</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs")
public class RepairStatsController {

    private static final Logger log = LoggerFactory.getLogger(RepairStatsController.class);

    private final AgentTaskRepository taskRepository;
    private final EvaluatorService    evaluatorService;

    public RepairStatsController(AgentTaskRepository taskRepository,
                                 EvaluatorService    evaluatorService) {
        this.taskRepository   = taskRepository;
        this.evaluatorService = evaluatorService;
    }

    /**
     * Returns repair statistics for a job.
     *
     * <p>Returns {@code 404} if the job has no SERVICE_GEN tasks (benchmark not run yet).
     */
    @GetMapping("/{id}/repair-stats")
    public ResponseEntity<RepairStats> repairStats(@PathVariable Long id) {
        List<AgentTask> tasks = taskRepository.findByJobIdAndTaskType(id, "SERVICE_GEN");
        if (tasks.isEmpty()) {
            log.warn("[repair-stats] No SERVICE_GEN tasks for job {}", id);
            return ResponseEntity.notFound().build();
        }

        int total = tasks.size();

        // First-attempt rate from agent_tasks.first_attempt_compiled
        List<AgentTask> tracked = tasks.stream()
                .filter(t -> t.getFirstAttemptCompiled() != null)
                .toList();
        long firstOk = tracked.stream()
                .filter(t -> Boolean.TRUE.equals(t.getFirstAttemptCompiled()))
                .count();
        double firstAttemptRate = tracked.isEmpty() ? 0.0 : (double) firstOk / tracked.size();

        // Final rate: prefer eval_metrics COMPILATION_POST_REPAIR, fall back to agent_tasks
        double finalRate = resolvePostRepairRate(id, tasks, total);

        // Average total compile attempts (repair_attempts + 1) per service
        OptionalDouble avg = tasks.stream()
                .filter(t -> t.getRepairAttempts() != null)
                .mapToInt(t -> t.getRepairAttempts() + 1)
                .average();
        double avgAttempts = avg.orElse(1.0);

        // Unrepaired: tracked services where first attempt failed and no repair data yet
        // or where output_data indicates finalCompiled=false
        int unrepairedCount = (int) Math.round(total * (1.0 - finalRate));

        RepairStats stats = new RepairStats(firstAttemptRate, finalRate, avgAttempts,
                unrepairedCount, total);

        log.info("[repair-stats] job {} — firstAttempt={:.3f} final={:.3f} avg={:.2f} unrepaired={}",
                id, firstAttemptRate, finalRate, avgAttempts, unrepairedCount);
        return ResponseEntity.ok(stats);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private double resolvePostRepairRate(Long jobId, List<AgentTask> tasks, int total) {
        try {
            List<EvalMetric> metrics = evaluatorService.findByJobId(jobId);
            return metrics.stream()
                    .filter(m -> m.getMetricName() == MetricName.COMPILATION_POST_REPAIR
                              && "multi-agent".equals(m.getSystemId()))
                    .findFirst()
                    .map(m -> m.getMetricValue().doubleValue())
                    .orElseGet(() -> computeFinalRateFromTasks(tasks, total));
        } catch (Exception e) {
            log.warn("[repair-stats] Could not read eval_metrics for job {}: {}", jobId, e.getMessage());
            return computeFinalRateFromTasks(tasks, total);
        }
    }

    /** Fallback: infer final rate from output_data.finalCompiled in agent_tasks. */
    private static double computeFinalRateFromTasks(List<AgentTask> tasks, int total) {
        if (total == 0) return 0.0;
        long finalOk = tasks.stream()
                .filter(t -> {
                    if (t.getOutputData() == null) return false;
                    Object v = t.getOutputData().get("finalCompiled");
                    return "true".equals(String.valueOf(v));
                })
                .count();
        return (double) finalOk / total;
    }
}
