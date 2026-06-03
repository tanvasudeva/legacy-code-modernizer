package com.legacy.modernizer.stream;

import java.util.HashMap;
import java.util.Map;

/**
 * Value object published to / consumed from Redis stream.
 * Serialised as a flat Map&lt;String, String&gt; so it fits Redis stream field format.
 */
public record AgentTaskMessage(
        String jobId,
        String classFqn,
        String taskType,   // REFACTOR | TEST_GEN | DOC_GEN
        String payload     // JSON-encoded extra context (nullable)
) {

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("jobId",    jobId);
        map.put("classFqn", classFqn);
        map.put("taskType", taskType);
        if (payload != null) map.put("payload", payload);
        return map;
    }

    public static AgentTaskMessage fromMap(Map<String, String> map) {
        return new AgentTaskMessage(
                map.get("jobId"),
                map.get("classFqn"),
                map.get("taskType"),
                map.get("payload")
        );
    }

    @Override
    public String toString() {
        return "AgentTaskMessage{jobId=" + jobId + ", classFqn=" + classFqn
                + ", taskType=" + taskType + "}";
    }
}
