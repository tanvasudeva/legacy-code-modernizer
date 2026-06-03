package com.legacy.modernizer.stream.consumer;

import com.legacy.modernizer.stream.AbstractStreamConsumer;
import com.legacy.modernizer.stream.AgentTaskMessage;
import com.legacy.modernizer.stream.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes DOC_GEN tasks from {@value StreamKeys#DOC_GENERATOR}.
 * Full LLM doc-generation logic wired in Phase 2.4.
 */
@Component
public class DocGeneratorConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocGeneratorConsumer.class);

    public DocGeneratorConsumer(StringRedisTemplate redisTemplate) {
        super(redisTemplate, StreamKeys.DOC_GENERATOR, "doc-generator-consumer-1");
    }

    @Override
    protected void process(AgentTaskMessage message) {
        log.info("[doc-generator] Processing {} — stub, full impl in Phase 2.4", message);
    }
}
