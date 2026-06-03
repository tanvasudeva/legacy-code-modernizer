package com.legacy.modernizer.stream.consumer;

import com.legacy.modernizer.stream.AbstractStreamConsumer;
import com.legacy.modernizer.stream.AgentTaskMessage;
import com.legacy.modernizer.stream.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes TEST_GEN tasks from {@value StreamKeys#TEST_WRITER}.
 * Full LLM test-generation logic wired in Phase 2.4.
 */
@Component
public class TestWriterConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(TestWriterConsumer.class);

    public TestWriterConsumer(StringRedisTemplate redisTemplate) {
        super(redisTemplate, StreamKeys.TEST_WRITER, "test-writer-consumer-1");
    }

    @Override
    protected void process(AgentTaskMessage message) {
        log.info("[test-writer] Processing {} — stub, full impl in Phase 2.4", message);
    }
}
