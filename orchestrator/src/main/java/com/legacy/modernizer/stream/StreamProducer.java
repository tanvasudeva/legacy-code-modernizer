package com.legacy.modernizer.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link AgentTaskMessage} objects to Redis stream keys.
 */
@Component
public class StreamProducer {

    private static final Logger log = LoggerFactory.getLogger(StreamProducer.class);

    private final StringRedisTemplate redisTemplate;

    public StreamProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Publishes a message to any stream key.
     *
     * @return the Redis {@link RecordId} assigned to the message
     */
    public RecordId publish(String streamKey, AgentTaskMessage message) {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(streamKey)
                .ofMap(message.toMap());
        RecordId id = redisTemplate.opsForStream().add(record);
        log.debug("Published to {}: {} → id={}", streamKey, message, id);
        return id;
    }

    public RecordId publishToRefactorer(AgentTaskMessage message) {
        return publish(StreamKeys.REFACTORER, message);
    }

    public RecordId publishToTestWriter(AgentTaskMessage message) {
        return publish(StreamKeys.TEST_WRITER, message);
    }

    public RecordId publishToDocGenerator(AgentTaskMessage message) {
        return publish(StreamKeys.DOC_GENERATOR, message);
    }
}
