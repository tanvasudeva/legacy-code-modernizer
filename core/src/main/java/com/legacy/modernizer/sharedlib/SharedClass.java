package com.legacy.modernizer.sharedlib;

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
@Table(name = "shared_classes",
        indexes = @Index(name = "idx_shared_classes_job_id", columnList = "job_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "class_fqn", nullable = false)
    private String classFqn;

    @Column(name = "service_count", nullable = false)
    private int serviceCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referencing_services", columnDefinition = "jsonb")
    private List<String> referencingServices;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
