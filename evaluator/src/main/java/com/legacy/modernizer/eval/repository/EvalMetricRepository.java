package com.legacy.modernizer.eval.repository;

import com.legacy.modernizer.eval.model.EvalMetric;
import com.legacy.modernizer.eval.model.MetricName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvalMetricRepository extends JpaRepository<EvalMetric, Long> {

    List<EvalMetric> findByJobId(Long jobId);

    List<EvalMetric> findByJobIdAndSystemId(Long jobId, String systemId);

    List<EvalMetric> findByJobIdAndMetricName(Long jobId, MetricName metricName);

    List<EvalMetric> findBySystemId(String systemId);
}
