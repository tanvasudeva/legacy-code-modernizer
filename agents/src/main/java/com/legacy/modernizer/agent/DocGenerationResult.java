package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;

import java.util.List;

/**
 * Return value of {@link DocGenAgent#generate}.
 *
 * @param task      the tracking {@link AgentTask} (COMPLETED or FAILED)
 * @param artifacts persisted rows — one each for openapi_spec, adr, runbook
 * @param files     in-memory {@link GeneratedFile} list (same content as artifacts)
 */
public record DocGenerationResult(
        AgentTask           task,
        List<Artifact>      artifacts,
        List<GeneratedFile> files
) {}
