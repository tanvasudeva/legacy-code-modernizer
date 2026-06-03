package com.legacy.modernizer.controller;

import com.legacy.modernizer.dto.AnalysisResult;
import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.junit.jupiter.api.*;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.Socket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalysisControllerIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MigrationJobRepository jobRepository;

    private static Long jobId;

    @BeforeAll
    static void checkServicesAvailable() {
        assumeTrue(portOpen("localhost", 5432), "PostgreSQL not available");
        assumeTrue(portOpen("localhost", 7687), "Neo4j not available");

        try (var driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "neo4j_password"));
             var session = driver.session()) {
            session.run("MATCH (n:Class)   DETACH DELETE n");
            session.run("MATCH (n:Package) DETACH DELETE n");
        }
    }

    @BeforeEach
    void seedJob() {
        if (jobId != null) return;
        Path petclinicSrc = Path.of("").toAbsolutePath().getParent()
                .resolve("benchmarks/spring-petclinic/src");
        jobId = jobRepository.save(MigrationJob.builder()
                .name("petclinic-test")
                .sourceDirectory(petclinicSrc.toAbsolutePath().toString())
                .status(JobStatus.PENDING)
                .build()).getId();
    }

    @AfterAll
    static void cleanup(@Autowired MigrationJobRepository repo) {
        if (jobId != null) repo.deleteById(jobId);
    }

    @Test @Order(1)
    void analyzeEndpointReturns200() {
        assertEquals(HttpStatus.OK, post().getStatusCode());
        assertNotNull(post().getBody());
    }

    @Test @Order(2)
    void responseContainsValidStats() {
        AnalysisResult r = post().getBody();
        assertEquals("COMPLETED", r.status());
        assertTrue(r.classNodes() > 0);
    }

    @Test @Order(3)
    void clusterCountIsInExpectedRange() {
        int n = post().getBody().clusterCount();
        assertTrue(n >= 2 && n <= 10, "Expected 2–10 clusters, got " + n);
    }

    @Test @Order(4)
    void clusterMapIsNonEmpty() {
        assertFalse(post().getBody().clusterMap().isEmpty());
    }

    @Test @Order(5)
    void neo4jIsPopulated() {
        try (var driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "neo4j_password"));
             var session = driver.session()) {
            long count = session.run("MATCH (c:Class) RETURN count(c) AS n").single().get("n").asLong();
            assertTrue(count > 0, "Expected Neo4j Class nodes > 0");
        }
    }

    @Test @Order(6)
    void jobStatusIsCompleted() {
        assertEquals(JobStatus.COMPLETED, jobRepository.findById(jobId).orElseThrow().getStatus());
    }

    @Test @Order(7)
    void clusterMapPersistedInMetadata() {
        var metadata = jobRepository.findById(jobId).orElseThrow().getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.containsKey("clusterMap"));
        assertTrue(metadata.containsKey("stats"));
    }

    @Test @Order(8)
    void notFoundReturns404() {
        assertEquals(HttpStatus.NOT_FOUND, restTemplate.postForEntity(
                "http://localhost:" + port + "/api/jobs/999999/analyze", null, String.class).getStatusCode());
    }

    private ResponseEntity<AnalysisResult> post() {
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/api/jobs/" + jobId + "/analyze",
                null, AnalysisResult.class);
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) { return true; } catch (Exception e) { return false; }
    }
}
