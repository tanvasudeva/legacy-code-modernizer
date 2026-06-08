package com.legacy.modernizer.eval.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * One row in {@code eval_metrics}: a single measured score for one job,
 * one metric, and one system (multi-agent or a single-prompt baseline).
 */
@Entity
@Table(name = "eval_metrics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** Nullable — some metrics are job-level, not artifact-level. */
    @Column(name = "artifact_id")
    private Long artifactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_name", nullable = false)
    private MetricName metricName;

    /** The measured value — 0.0–1.0 for rates, 1.0–10.0 for LLM judge. */
    @Column(name = "metric_value", precision = 10, scale = 4)
    private BigDecimal metricValue;

    /**
     * Which system produced this result.
     * Values: {@code "multi-agent"}, {@code "single-prompt-claude"},
     * {@code "single-prompt-gpt4o"}.
     */
    @Column(name = "system_id", nullable = false)
    private String systemId;

    /** Corresponding single-prompt baseline value, if pre-populated for comparison. */
    @Column(name = "baseline", precision = 10, scale = 4)
    private BigDecimal baseline;

    /** Detailed breakdown stored as JSONB (sub-scores, error counts, etc.). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "measured_at", nullable = false, updatable = false)
    private LocalDateTime measuredAt;

    @PrePersist
    void onPersist() {
        if (measuredAt == null) measuredAt = LocalDateTime.now();
    }
}
