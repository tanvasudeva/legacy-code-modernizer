package com.legacy.modernizer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "service_boundaries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceBoundary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "class_fqns", columnDefinition = "jsonb", nullable = false)
    private List<String> classFqns;

    /** DDD domain context — 2-3 sentence description of the bounded context. */
    @Column(name = "description")
    private String description;

    /** LLM rationale explaining why these classes form a cohesive bounded context. */
    @Column(name = "rationale")
    private String rationale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
