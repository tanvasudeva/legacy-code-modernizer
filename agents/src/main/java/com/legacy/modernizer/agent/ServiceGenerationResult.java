package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;

import java.util.List;

/**
 * Return value of {@link ServiceGeneratorAgent#generate}.
 *
 * @param task      the tracking {@link AgentTask} (COMPLETED or FAILED)
 * @param artifacts persisted {@link Artifact} rows, one per generated file
 * @param files     in-memory {@link GeneratedFile} list — same content as the artifacts
 */
public record ServiceGenerationResult(
        AgentTask       task,
        List<Artifact>  artifacts,
        List<GeneratedFile> files
) {}
