package com.legacy.modernizer.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Base class for all Redis stream consumers.
 *
 * <p>Subclasses provide the stream key and implement {@link #process(AgentTaskMessage)}.
 * Each call to {@link #poll()} executes one XREADGROUP → process → XACK cycle.
 *
 * <pre>
 * XREADGROUP GROUP &lt;group&gt; &lt;consumerName&gt; COUNT 10 STREAMS &lt;key&gt; &gt;
 *   → for each record: process(message)
 *   → XACK &lt;key&gt; &lt;group&gt; &lt;id&gt;
 * </pre>
 */
public abstract class AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AbstractStreamConsumer.class);
    private static final int BATCH_SIZE = 10;

    protected final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;

    protected AbstractStreamConsumer(StringRedisTemplate redisTemplate,
                                     String streamKey,
                                     String consumerName) {
        this.redisTemplate  = redisTemplate;
        this.streamKey      = streamKey;
        this.consumerGroup  = StreamKeys.CONSUMER_GROUP;
        this.consumerName   = consumerName;
    }

    /**
     * Reads up to {@value BATCH_SIZE} pending messages via XREADGROUP,
     * calls {@link #process(AgentTaskMessage)} for each, then XACKs on success.
     *
     * @return number of messages processed in this batch
     */
    public int poll() {
        List<MapRecord<String, String, String>> records;
        try {
            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> raw = (List<MapRecord<String, String, String>>) (List<?>)
                    redisTemplate.opsForStream().read(
                            Consumer.from(consumerGroup, consumerName),
                            StreamReadOptions.empty().count(BATCH_SIZE),
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                    );
            records = raw;
        } catch (Exception e) {
            log.warn("XREADGROUP failed on {}: {}", streamKey, e.getMessage());
            return 0;
        }

        if (records == null || records.isEmpty()) return 0;

        int processed = 0;
        for (MapRecord<String, String, String> record : records) {
            try {
                AgentTaskMessage message = AgentTaskMessage.fromMap(record.getValue());
                log.debug("[{}] Processing {}", consumerName, message);
                process(message);
                // XACK — remove from pending-entry list
                redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, record.getId());
                processed++;
            } catch (Exception e) {
                log.error("[{}] Failed to process record {}: {}", consumerName, record.getId(), e.getMessage(), e);
                // Do NOT ack — message stays in PEL for redelivery
            }
        }
        log.debug("[{}] Processed {}/{} messages from {}", consumerName, processed, records.size(), streamKey);
        return processed;
    }

    /** Subclasses implement domain-specific handling of each task message. */
    protected abstract void process(AgentTaskMessage message);

    public String getStreamKey()    { return streamKey;    }
    public String getConsumerGroup(){ return consumerGroup; }
    public String getConsumerName() { return consumerName; }
}
