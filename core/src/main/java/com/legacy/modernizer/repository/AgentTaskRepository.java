package com.legacy.modernizer.repository;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByJobId(Long jobId);

    List<AgentTask> findByJobIdAndStatus(Long jobId, AgentTaskStatus status);

    Optional<AgentTask> findByJobIdAndClassFqnAndTaskType(Long jobId, String classFqn, String taskType);

    List<AgentTask> findByJobIdAndTaskType(Long jobId, String taskType);
}
