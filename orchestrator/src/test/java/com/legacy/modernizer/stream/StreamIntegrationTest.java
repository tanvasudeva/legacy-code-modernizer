package com.legacy.modernizer.stream;

import com.legacy.modernizer.stream.consumer.RefactorerConsumer;
import com.legacy.modernizer.stream.consumer.TestWriterConsumer;
import com.legacy.modernizer.stream.consumer.DocGeneratorConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamIntegrationTest {

    @Autowired private StreamProducer producer;
    @Autowired private RefactorerConsumer refactorerConsumer;
    @Autowired private TestWriterConsumer testWriterConsumer;
    @Autowired private DocGeneratorConsumer docGeneratorConsumer;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void requireRedis() {
        assumeTrue(portOpen("localhost", 6379), "Redis not available — start Docker services");
    }

    @AfterAll
    static void cleanup(@Autowired StringRedisTemplate tpl) {
        for (String key : StreamKeys.ALL) tpl.delete(key);
    }

    // -------------------------------------------------------------------------

    @Test @Order(1)
    void allThreeStreamsExistAfterStartup() {
        for (String key : StreamKeys.ALL) {
            assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(key)),
                    "Stream should exist: " + key);
        }
    }

    @Test @Order(2)
    void consumerGroupExistsOnAllStreams() {
        for (String key : StreamKeys.ALL) {
            assertDoesNotThrow(() -> {
                redisTemplate.opsForStream().groups(key);
            }, "XINFO GROUPS should succeed on: " + key);
        }
    }

    @Test @Order(3)
    void publishToRefactorerReturnsRecordId() {
        AgentTaskMessage msg = new AgentTaskMessage("1", "com.example.Owner", "REFACTOR", null);
        RecordId id = producer.publishToRefactorer(msg);
        assertNotNull(id, "RecordId should not be null");
        assertFalse(id.getValue().isBlank(), "RecordId value should not be blank");
    }

    @Test @Order(4)
    void publishToTestWriterReturnsRecordId() {
        AgentTaskMessage msg = new AgentTaskMessage("1", "com.example.Pet", "TEST_GEN", "{\"framework\":\"junit5\"}");
        RecordId id = producer.publishToTestWriter(msg);
        assertNotNull(id);
    }

    @Test @Order(5)
    void publishToDocGeneratorReturnsRecordId() {
        AgentTaskMessage msg = new AgentTaskMessage("1", "com.example.Vet", "DOC_GEN", null);
        RecordId id = producer.publishToDocGenerator(msg);
        assertNotNull(id);
    }

    @Test @Order(6)
    void refactorerConsumerReadsAndAcknowledgesMessage() {
        // Publish a message
        AgentTaskMessage msg = new AgentTaskMessage("42", "com.example.OwnerController", "REFACTOR", null);
        producer.publishToRefactorer(msg);

        // Poll — should read and ACK the message
        int processed = refactorerConsumer.poll();
        assertTrue(processed >= 1, "Expected at least 1 message processed, got " + processed);

        // After ACK, pending entry list should have 0 unacknowledged messages
        PendingMessagesSummary pending = redisTemplate.opsForStream()
                .pending(StreamKeys.REFACTORER, StreamKeys.CONSUMER_GROUP);
        assertEquals(0, pending.getTotalPendingMessages(),
                "All messages should be acknowledged");
    }

    @Test @Order(7)
    void testWriterConsumerReadsAndAcknowledgesMessage() {
        producer.publishToTestWriter(
                new AgentTaskMessage("42", "com.example.PetController", "TEST_GEN", null));
        int processed = testWriterConsumer.poll();
        assertTrue(processed >= 1);
        PendingMessagesSummary pending = redisTemplate.opsForStream()
                .pending(StreamKeys.TEST_WRITER, StreamKeys.CONSUMER_GROUP);
        assertEquals(0, pending.getTotalPendingMessages());
    }

    @Test @Order(8)
    void docGeneratorConsumerReadsAndAcknowledgesMessage() {
        producer.publishToDocGenerator(
                new AgentTaskMessage("42", "com.example.VetController", "DOC_GEN", null));
        int processed = docGeneratorConsumer.poll();
        assertTrue(processed >= 1);
        PendingMessagesSummary pending = redisTemplate.opsForStream()
                .pending(StreamKeys.DOC_GENERATOR, StreamKeys.CONSUMER_GROUP);
        assertEquals(0, pending.getTotalPendingMessages());
    }

    @Test @Order(9)
    void agentTaskMessageRoundTrips() {
        AgentTaskMessage original = new AgentTaskMessage("99", "com.example.Foo", "REFACTOR", "{\"key\":\"val\"}");
        AgentTaskMessage roundTripped = AgentTaskMessage.fromMap(original.toMap());
        assertEquals(original.jobId(),    roundTripped.jobId());
        assertEquals(original.classFqn(), roundTripped.classFqn());
        assertEquals(original.taskType(), roundTripped.taskType());
        assertEquals(original.payload(),  roundTripped.payload());
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) { return true; } catch (Exception e) { return false; }
    }
}
