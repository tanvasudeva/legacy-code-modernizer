package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;

import java.util.List;

/**
 * Return value of {@link TestWriterAgent#generate}.
 *
 * @param task      the tracking {@link AgentTask} (COMPLETED or FAILED)
 * @param artifacts persisted {@link Artifact} rows, one per generated test file
 * @param files     in-memory {@link GeneratedFile} list, same order as artifacts
 */
public record TestGenerationResult(
        AgentTask           task,
        List<Artifact>      artifacts,
        List<GeneratedFile> files
) {}
