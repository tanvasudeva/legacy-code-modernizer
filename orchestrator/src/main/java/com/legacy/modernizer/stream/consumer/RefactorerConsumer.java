package com.legacy.modernizer.stream.consumer;

import com.legacy.modernizer.stream.AbstractStreamConsumer;
import com.legacy.modernizer.stream.AgentTaskMessage;
import com.legacy.modernizer.stream.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes REFACTOR tasks from {@value StreamKeys#REFACTORER}.
 * Full LLM refactoring logic wired in Phase 2.4.
 */
@Component
public class RefactorerConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefactorerConsumer.class);

    public RefactorerConsumer(StringRedisTemplate redisTemplate) {
        super(redisTemplate, StreamKeys.REFACTORER, "refactorer-consumer-1");
    }

    @Override
    protected void process(AgentTaskMessage message) {
        log.info("[refactorer] Processing {} — stub, full impl in Phase 2.4", message);
    }
}
