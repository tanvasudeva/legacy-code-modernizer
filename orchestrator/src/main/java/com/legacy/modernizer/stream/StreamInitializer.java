package com.legacy.modernizer.stream;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Creates Redis stream consumer groups for all agent streams on application startup.
 * Uses XGROUP CREATE with MKSTREAM semantics (creates stream if absent).
 */
@Component
public class StreamInitializer {

    private static final Logger log = LoggerFactory.getLogger(StreamInitializer.class);

    private final StringRedisTemplate redisTemplate;

    public StreamInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void initStreams() {
        for (String streamKey : StreamKeys.ALL) {
            ensureStreamExists(streamKey);
            createGroupIfAbsent(streamKey, StreamKeys.CONSUMER_GROUP);
        }
        log.info("Redis stream consumer groups ready: {} on streams {}",
                StreamKeys.CONSUMER_GROUP, String.join(", ", StreamKeys.ALL));
    }

    /** Publishes a no-op init record so the stream key exists before XGROUP CREATE. */
    private void ensureStreamExists(String streamKey) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
            MapRecord<String, String, String> init = StreamRecords.newRecord()
                    .in(streamKey)
                    .ofMap(Map.of("_init", "true"));
            redisTemplate.opsForStream().add(init);
            log.debug("Created stream: {}", streamKey);
        }
    }

    private void createGroupIfAbsent(String streamKey, String groupName) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), groupName);
            log.info("Created consumer group '{}' on stream '{}'", groupName, streamKey);
        } catch (RedisSystemException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on '{}'", groupName, streamKey);
            } else {
                throw e;
            }
        }
    }
}
