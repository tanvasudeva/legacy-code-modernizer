package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;

/**
 * Returned by {@link RefactorerAgent#refactor} — bundles the tracking task
 * and both the original and transformed source artifacts.
 */
public record RefactorResult(AgentTask task, Artifact original, Artifact transformed) {}
