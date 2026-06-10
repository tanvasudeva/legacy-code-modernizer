package com.legacy.modernizer.bundle;

import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.agent.CompilationRepairService;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ★ Phase 3.4 End-to-End milestone.
 *
 * <p>Uses pre-baked canonical owner-service source files (the same template
 * produced by the Phase 3.1 ServiceGeneratorAgent) stored as {@code SERVICE_CODE}
 * artifacts in PostgreSQL.  Does NOT require an LLM or ANTHROPIC_API_KEY.
 *
 * <p>Flow:
 * <ol>
 *   <li>Seed: create a MigrationJob + 7 SERVICE_CODE artifacts for {@code owner-service}</li>
 *   <li>GET /api/jobs/{id}/bundle → response must be 200 with application/zip content-type</li>
 *   <li>Unzip the response body to a temp directory</li>
 *   <li>Assert ZIP structure: root pom.xml, docker-compose.yml, all 7 service files present</li>
 *   <li>Run {@code mvn compile -q} inside {@code owner-service/} sub-directory</li>
 *   <li>Assert exit code 0 (compilation success)</li>
 * </ol>
 *
 * <p>Skipped automatically when PostgreSQL is not reachable on localhost:5432.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BundleEndToEndTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate          restTemplate;
    @Autowired private MigrationJobRepository    jobRepository;
    @Autowired private ArtifactRepository        artifactRepository;
    @Autowired private AgentTaskRepository       taskRepository;
    @Autowired private CompilationRepairService  repairService;

    private static Long jobId;

    // -------------------------------------------------------------------------
    // Canonical owner-service sources — guaranteed to compile with Spring Boot 3.2.5
    // -------------------------------------------------------------------------

    private static final String SERVICE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>

                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.5</version>
                    <relativePath/>
                </parent>

                <groupId>com.modernized</groupId>
                <artifactId>owner-service</artifactId>
                <version>1.0.0-SNAPSHOT</version>

                <properties>
                    <java.version>21</java.version>
                    <maven.compiler.source>21</maven.compiler.source>
                    <maven.compiler.target>21</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.postgresql</groupId>
                        <artifactId>postgresql</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <optional>true</optional>
                    </dependency>
                </dependencies>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <configuration>
                                <excludes>
                                    <exclude>
                                        <groupId>org.projectlombok</groupId>
                                        <artifactId>lombok</artifactId>
                                    </exclude>
                                </excludes>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    private static final String APPLICATION_JAVA = """
            package com.modernized.ownerservice;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class Application {
                public static void main(String[] args) {
                    SpringApplication.run(Application.class, args);
                }
            }
            """;

    private static final String OWNER_ENTITY = """
            package com.modernized.ownerservice.entity;

            import jakarta.persistence.*;
            import lombok.*;

            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            @Entity
            @Table(name = "owners")
            public class Owner {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;
                private String firstName;
                private String lastName;
                private String address;
                private String city;
                private String telephone;
            }
            """;

    private static final String OWNER_REQUEST_DTO = """
            package com.modernized.ownerservice.dto;

            import lombok.AllArgsConstructor;
            import lombok.Data;
            import lombok.NoArgsConstructor;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public class OwnerRequest {
                private String firstName;
                private String lastName;
                private String address;
                private String city;
                private String telephone;
            }
            """;

    private static final String OWNER_REPOSITORY = """
            package com.modernized.ownerservice.repository;

            import com.modernized.ownerservice.entity.Owner;
            import org.springframework.data.jpa.repository.JpaRepository;

            public interface OwnerRepository extends JpaRepository<Owner, Long> {}
            """;

    private static final String OWNER_SERVICE = """
            package com.modernized.ownerservice.service;

            import com.modernized.ownerservice.dto.OwnerRequest;
            import com.modernized.ownerservice.entity.Owner;
            import com.modernized.ownerservice.repository.OwnerRepository;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import java.util.List;

            @Service
            @Transactional
            public class OwnerService {

                private final OwnerRepository repo;

                public OwnerService(OwnerRepository repo) {
                    this.repo = repo;
                }

                public List<Owner> findAll() {
                    return repo.findAll();
                }

                public Owner findById(Long id) {
                    return repo.findById(id).orElseThrow();
                }

                public Owner create(OwnerRequest req) {
                    return repo.save(Owner.builder()
                            .firstName(req.getFirstName())
                            .lastName(req.getLastName())
                            .address(req.getAddress())
                            .city(req.getCity())
                            .telephone(req.getTelephone())
                            .build());
                }

                public Owner update(Long id, OwnerRequest req) {
                    Owner o = findById(id);
                    o.setFirstName(req.getFirstName());
                    o.setLastName(req.getLastName());
                    o.setAddress(req.getAddress());
                    o.setCity(req.getCity());
                    o.setTelephone(req.getTelephone());
                    return repo.save(o);
                }

                public void delete(Long id) {
                    repo.deleteById(id);
                }
            }
            """;

    private static final String OWNER_CONTROLLER = """
            package com.modernized.ownerservice.controller;

            import com.modernized.ownerservice.dto.OwnerRequest;
            import com.modernized.ownerservice.entity.Owner;
            import com.modernized.ownerservice.service.OwnerService;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;
            import java.util.List;

            @RestController
            @RequestMapping("/api/owners")
            public class OwnerController {

                private final OwnerService svc;

                public OwnerController(OwnerService svc) {
                    this.svc = svc;
                }

                @GetMapping
                public List<Owner> list() {
                    return svc.findAll();
                }

                @GetMapping("/{id}")
                public ResponseEntity<Owner> get(@PathVariable Long id) {
                    return ResponseEntity.ok(svc.findById(id));
                }

                @PostMapping
                public ResponseEntity<Owner> create(@RequestBody OwnerRequest req) {
                    return ResponseEntity.ok(svc.create(req));
                }

                @PutMapping("/{id}")
                public ResponseEntity<Owner> update(@PathVariable Long id,
                                                    @RequestBody OwnerRequest req) {
                    return ResponseEntity.ok(svc.update(id, req));
                }

                @DeleteMapping("/{id}")
                public ResponseEntity<Void> delete(@PathVariable Long id) {
                    svc.delete(id);
                    return ResponseEntity.noContent().build();
                }
            }
            """;

    // -------------------------------------------------------------------------
    // Fixture map: ZIP path suffix → source content
    // -------------------------------------------------------------------------

    private static final List<String[]> SERVICE_FILES = List.of(
            new String[]{"pom.xml",                                                                    SERVICE_POM},
            new String[]{"src/main/java/com/modernized/ownerservice/Application.java",                 APPLICATION_JAVA},
            new String[]{"src/main/java/com/modernized/ownerservice/entity/Owner.java",                OWNER_ENTITY},
            new String[]{"src/main/java/com/modernized/ownerservice/dto/OwnerRequest.java",            OWNER_REQUEST_DTO},
            new String[]{"src/main/java/com/modernized/ownerservice/repository/OwnerRepository.java",  OWNER_REPOSITORY},
            new String[]{"src/main/java/com/modernized/ownerservice/service/OwnerService.java",        OWNER_SERVICE},
            new String[]{"src/main/java/com/modernized/ownerservice/controller/OwnerController.java",  OWNER_CONTROLLER}
    );

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(portOpen("localhost", 5432), "PostgreSQL not available — start Docker services");
    }

    @BeforeAll
    static void seedArtifacts(@Autowired MigrationJobRepository jobRepo,
                               @Autowired ArtifactRepository artifactRepo) {
        MigrationJob job = jobRepo.save(MigrationJob.builder()
                .name("e2e-bundle-test")
                .sourceDirectory("/tmp/petclinic")
                .status(JobStatus.PENDING)
                .build());
        jobId = job.getId();

        for (String[] f : SERVICE_FILES) {
            artifactRepo.save(Artifact.builder()
                    .jobId(jobId)
                    .artifactType(ArtifactType.SERVICE_CODE)
                    .classFqn("owner-service")
                    .filePath(f[0])
                    .content(f[1])
                    .build());
        }
    }

    @AfterAll
    static void cleanup(@Autowired MigrationJobRepository jobRepo) {
        if (jobId != null) jobRepo.deleteById(jobId);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test @Order(1)
    void bundleEndpointReturns200() {
        ResponseEntity<byte[]> r = restTemplate.getForEntity(url(), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(2)
    void contentTypeIsZip() {
        ResponseEntity<byte[]> r = restTemplate.getForEntity(url(), byte[].class);
        assertThat(r.getHeaders().getContentType()).isNotNull();
        assertThat(r.getHeaders().getContentType().toString()).contains("zip");
    }

    @Test @Order(3)
    void contentDispositionHasFilename() {
        ResponseEntity<byte[]> r = restTemplate.getForEntity(url(), byte[].class);
        String cd = r.getHeaders().getFirst("Content-Disposition");
        assertThat(cd).isNotNull().contains("attachment").contains("bundle.zip");
    }

    @Test @Order(4)
    void zipContainsRootPomAndDockerCompose() throws Exception {
        byte[] zip = download();
        var entries = listEntries(zip);
        assertThat(entries).contains("pom.xml", "docker-compose.yml");
    }

    @Test @Order(5)
    void rootPomListsOwnerServiceModule() throws Exception {
        byte[] zip = download();
        String rootPom = readEntry(zip, "pom.xml");
        assertThat(rootPom).contains("<module>owner-service</module>");
    }

    @Test @Order(6)
    void dockerComposeContainsOwnerService() throws Exception {
        byte[] zip = download();
        String dc = readEntry(zip, "docker-compose.yml");
        assertThat(dc).contains("owner-service:");
        assertThat(dc).contains("db:");
    }

    @Test @Order(7)
    void zipContainsAllSevenServiceFiles() throws Exception {
        byte[] zip = download();
        var entries = listEntries(zip);
        for (String[] f : SERVICE_FILES) {
            assertThat(entries).contains("owner-service/" + f[0]);
        }
    }

    @Test @Order(8)
    void unknownJobReturns404() {
        ResponseEntity<byte[]> r = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/jobs/999999/bundle", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * ★ Milestone: unzip the bundle, run {@code mvn compile -q} inside the
     * {@code owner-service} sub-directory, and assert exit code 0.
     */
    @Test @Order(9)
    void mvnCompilePassesOnGeneratedOwnerService() throws Exception {
        Path tempDir = Files.createTempDirectory("lcm-e2e-");
        try {
            // 1. Unzip bundle into tempDir
            unzip(download(), tempDir);

            Path serviceDir = tempDir.resolve("owner-service");
            assertThat(serviceDir).isDirectory();

            // 2. Run mvn compile -q
            String mvnCmd = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";

            ProcessBuilder pb = new ProcessBuilder(mvnCmd, "compile", "-q",
                    "--no-transfer-progress")
                    .directory(serviceDir.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();

            if (exit != 0) {
                System.out.println("=== mvn compile output ===\n" + output);
            }

            assertThat(exit)
                    .as("mvn compile must exit 0 — output:\n" + output)
                    .isZero();

        } finally {
            deleteTree(tempDir);
        }
    }

    /**
     * Phase 4.7 repair-loop test.
     *
     * <p>Seeds a service with a deliberately broken Java file (missing import),
     * runs {@link CompilationRepairService#compileWithRepair} with maxAttempts=3,
     * and verifies that:
     * <ul>
     *   <li>{@code first_attempt_compiled} was recorded as {@code false}</li>
     *   <li>{@code repair_attempts} is ≥ 1 (the loop actually ran)</li>
     * </ul>
     *
     * <p>Skipped when PostgreSQL or {@code ANTHROPIC_API_KEY} is unavailable —
     * the repair LLM call requires a live Claude model.
     */
    @Test @Order(10)
    void repairLoopActivatesOnBrokenArtifact() {
        assumeTrue(portOpen("localhost", 5432), "PostgreSQL not available");
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null,
                "ANTHROPIC_API_KEY not set — repair LLM call requires Claude");

        // 1. Seed a job with a broken service (missing import for ResponseEntity)
        MigrationJob repairJob = jobRepository.save(MigrationJob.builder()
                .name("repair-test-job")
                .sourceDirectory("/tmp/repair-test")
                .status(JobStatus.PENDING)
                .build());
        Long repairJobId = repairJob.getId();

        try {
            // pom.xml — valid
            artifactRepository.save(Artifact.builder()
                    .jobId(repairJobId).artifactType(ArtifactType.SERVICE_CODE)
                    .classFqn("broken-service").filePath("pom.xml").content(SERVICE_POM)
                    .build());

            // Controller with deliberately missing import → compile error
            String brokenController = """
                    package com.modernized.brokenservice.controller;

                    import com.modernized.brokenservice.service.OwnerService;
                    // Missing: import org.springframework.http.ResponseEntity;

                    import org.springframework.web.bind.annotation.*;
                    import java.util.List;

                    @RestController
                    @RequestMapping("/api/broken")
                    public class BrokenController {
                        private final OwnerService svc;
                        public BrokenController(OwnerService svc) { this.svc = svc; }

                        @GetMapping
                        public ResponseEntity<List<String>> list() {   // ResponseEntity not imported
                            return ResponseEntity.ok(List.of("a", "b"));
                        }
                    }
                    """;

            List<Artifact> brokenArtifacts = List.of(
                    artifactRepository.save(Artifact.builder()
                            .jobId(repairJobId).artifactType(ArtifactType.SERVICE_CODE)
                            .classFqn("broken-service")
                            .filePath("src/main/java/com/modernized/brokenservice/Application.java")
                            .content(APPLICATION_JAVA.replace("ownerservice", "brokenservice"))
                            .build()),
                    artifactRepository.save(Artifact.builder()
                            .jobId(repairJobId).artifactType(ArtifactType.SERVICE_CODE)
                            .classFqn("broken-service")
                            .filePath("src/main/java/com/modernized/brokenservice/controller/BrokenController.java")
                            .content(brokenController)
                            .build())
            );

            // 2. Create a tracking task
            AgentTask task = taskRepository.save(AgentTask.builder()
                    .jobId(repairJobId).taskType("SERVICE_GEN")
                    .status(AgentTaskStatus.IN_PROGRESS)
                    .classFqn("broken-service")
                    .build());

            // 3. Run repair loop
            CompilationRepairService.CompilationRepairResult result =
                    repairService.compileWithRepair(task, brokenArtifacts, 3);

            // 4. Reload task from DB to verify tracking columns were written
            AgentTask saved = taskRepository.findById(task.getId()).orElseThrow();

            assertThat(saved.getFirstAttemptCompiled())
                    .as("first_attempt_compiled must be set after repair run")
                    .isNotNull();
            assertThat(saved.getFirstAttemptCompiled())
                    .as("first attempt must have failed (broken import)")
                    .isFalse();
            assertThat(saved.getRepairAttempts())
                    .as("repair_attempts must be ≥ 1 (loop ran at least once)")
                    .isGreaterThanOrEqualTo(1);

            // If the LLM successfully fixed the import the final result is a bonus pass
            if (result.success()) {
                assertThat(result.totalAttempts())
                        .as("should take more than 1 attempt when first compile failed")
                        .isGreaterThan(1);
            }

        } finally {
            jobRepository.deleteById(repairJobId);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String url() {
        return "http://localhost:" + port + "/api/jobs/" + jobId + "/bundle";
    }

    private byte[] download() {
        ResponseEntity<byte[]> r = restTemplate.getForEntity(url(), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static List<String> listEntries(byte[] zip) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
                zis.closeEntry();
            }
        }
        return names;
    }

    private static String readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) return new String(zis.readAllBytes());
                zis.closeEntry();
            }
        }
        throw new AssertionError("Entry not found in ZIP: " + name);
    }

    private static void unzip(byte[] zip, Path dest) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest)) throw new SecurityException("Zip slip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
    }

    private static boolean portOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) { return true; } catch (Exception e) { return false; }
    }
}
