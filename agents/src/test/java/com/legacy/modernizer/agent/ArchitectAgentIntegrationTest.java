package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for ArchitectAgent against real Claude API + PostgreSQL.
 * Skips if ANTHROPIC_API_KEY is not set or if PostgreSQL is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArchitectAgentIntegrationTest {

    private static final Map<String, String> PETCLINIC_CLUSTER_MAP = Map.ofEntries(
            Map.entry("org.springframework.samples.petclinic.owner.Owner",           "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.OwnerController", "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.OwnerRepository", "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.Pet",             "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.PetController",   "owner-service"),
            Map.entry("org.springframework.samples.petclinic.vet.Vet",              "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.VetController",    "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.Specialty",        "vet-service"),
            Map.entry("org.springframework.samples.petclinic.owner.Visit",          "visit-service"),
            Map.entry("org.springframework.samples.petclinic.owner.VisitController","visit-service"),
            Map.entry("org.springframework.samples.petclinic.model.BaseEntity",     "model-service"),
            Map.entry("org.springframework.samples.petclinic.model.NamedEntity",    "model-service"),
            Map.entry("org.springframework.samples.petclinic.system.CacheConfiguration","system-service"),
            Map.entry("org.springframework.samples.petclinic.system.WelcomeController", "system-service")
    );

    @Autowired private ArchitectAgent architectAgent;
    @Autowired private MigrationJobRepository jobRepository;
    @Autowired private ServiceBoundaryRepository boundaryRepository;

    private static Long jobId;

    @BeforeAll
    static void preconditions() {
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null,
                "ANTHROPIC_API_KEY not set — skipping integration test");
        assumeTrue(portOpen("localhost", 5432),
                "PostgreSQL not available — start Docker services");
    }

    @BeforeAll
    static void createJob(@Autowired MigrationJobRepository repo) {
        MigrationJob job = repo.save(MigrationJob.builder()
                .name("architect-agent-test")
                .sourceDirectory("/tmp/petclinic")
                .status(JobStatus.ANALYZING)
                .build());
        jobId = job.getId();
    }

    @AfterAll
    static void cleanup(@Autowired MigrationJobRepository jobRepo,
                        @Autowired ServiceBoundaryRepository boundaryRepo) {
        if (jobId != null) {
            boundaryRepo.deleteByJobId(jobId);
            jobRepo.deleteById(jobId);
        }
    }

    @Test @Order(1)
    void produces4to7ServiceBoundaries() {
        List<ServiceBoundary> boundaries = architectAgent.analyze(jobId, PETCLINIC_CLUSTER_MAP);
        int count = boundaries.size();
        assertTrue(count >= 4 && count <= 7,
                "Expected 4–7 service boundaries, got " + count);
    }

    @Test @Order(2)
    void boundariesPersistedInDatabase() {
        List<ServiceBoundary> saved = boundaryRepository.findByJobId(jobId);
        assertFalse(saved.isEmpty(), "Boundaries should be persisted in DB");
    }

    @Test @Order(3)
    void allBoundariesHaveRationaleInDb() {
        boundaryRepository.findByJobId(jobId).forEach(b ->
                assertNotNull(b.getRationale(),
                        "Rationale missing for service: " + b.getServiceName()));
    }

    @Test @Order(4)
    void allBoundariesHaveDomainContextInDb() {
        boundaryRepository.findByJobId(jobId).forEach(b ->
                assertNotNull(b.getDescription(),
                        "Domain context missing for: " + b.getServiceName()));
    }

    @Test @Order(5)
    void allBoundariesHaveClassFqnsInDb() {
        boundaryRepository.findByJobId(jobId).forEach(b ->
                assertFalse(b.getClassFqns().isEmpty(),
                        "No classes for: " + b.getServiceName()));
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) { return true; } catch (Exception e) { return false; }
    }
}
