package com.legacy.modernizer.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.agent.RefactorerAgent;
import com.legacy.modernizer.stream.AbstractStreamConsumer;
import com.legacy.modernizer.stream.AgentTaskMessage;
import com.legacy.modernizer.stream.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes REFACTOR tasks from {@value StreamKeys#REFACTORER} and delegates to
 * {@link RefactorerAgent} for class-level LLM transformation.
 *
 * <p>Expected {@code payload} JSON:
 * <pre>
 * {
 *   "sourceCode":    "&lt;full Java source text&gt;",
 *   "serviceName":   "owner-service",
 *   "domainContext": "2-3 sentence DDD description",
 *   "filePath":      "src/main/java/..."   (optional)
 * }
 * </pre>
 */
@Component
public class RefactorerConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefactorerConsumer.class);

    private final RefactorerAgent refactorerAgent;
    private final ObjectMapper objectMapper;

    public RefactorerConsumer(StringRedisTemplate redisTemplate,
                              RefactorerAgent refactorerAgent,
                              ObjectMapper objectMapper) {
        super(redisTemplate, StreamKeys.REFACTORER, "refactorer-consumer-1");
        this.refactorerAgent = refactorerAgent;
        this.objectMapper    = objectMapper;
    }

    @Override
    protected void process(AgentTaskMessage message) {
        log.info("[refactorer] Received task for class {} (job {})", message.classFqn(), message.jobId());

        RefactorPayload payload = parsePayload(message);
        Long jobId = Long.parseLong(message.jobId());

        refactorerAgent.refactor(
                jobId,
                message.classFqn(),
                payload.sourceCode(),
                payload.serviceName(),
                payload.domainContext(),
                payload.filePath()
        );
    }

    private RefactorPayload parsePayload(AgentTaskMessage message) {
        String raw = message.payload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing payload for REFACTOR task on class " + message.classFqn());
        }
        try {
            return objectMapper.readValue(raw, RefactorPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid REFACTOR payload for " + message.classFqn() + ": " + e.getMessage(), e);
        }
    }

    record RefactorPayload(
            String sourceCode,
            String serviceName,
            String domainContext,
            String filePath
    ) {}
}
