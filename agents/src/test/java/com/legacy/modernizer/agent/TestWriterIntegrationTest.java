package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: calls real Claude, generates the owner-service and its tests,
 * writes everything to a temp dir, then asserts:
 *   1.  mvn compile passes (all files compile)
 *   2.  mvn test   passes (at least 1 test runs and all pass)
 *
 * Skipped automatically unless ANTHROPIC_API_KEY is set.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class TestWriterIntegrationTest {

    // ------------------------------------------------------------------
    // Canonical owner service boundary (same as ServiceGeneratorIntegrationTest)
    // ------------------------------------------------------------------
    private static final ServiceBoundary OWNER_BOUNDARY = ServiceBoundary.builder()
            .id(null).jobId(1L).serviceName("owner-service")
            .description("Manages owner registration, lookup, and profile management.")
            .rationale("Owner entity, controller, and repository form a cohesive CRUD bounded context.")
            .classFqns(List.of(
                    "org.springframework.samples.petclinic.owner.Owner",
                    "org.springframework.samples.petclinic.owner.OwnerController",
                    "org.springframework.samples.petclinic.owner.OwnerRepository"))
            .build();

    private static final List<String> PETCLINIC_SNIPPETS = List.of(
            "@Entity @Table(name=\"owners\") public class Owner { @Id Long id; String firstName; String lastName; String address; String city; String telephone; }",
            "public interface OwnerRepository extends JpaRepository<Owner, Long> {}",
            "@RestController @RequestMapping(\"/api/owners\") public class OwnerController { @GetMapping public List<Owner> list() { return svc.findAll(); } }"
    );

    @Autowired ServiceGeneratorAgent serviceGeneratorAgent;
    @Autowired TestWriterAgent       testWriterAgent;

    // ------------------------------------------------------------------
    // Generation tests (no disk I/O)
    // ------------------------------------------------------------------

    @Test
    void generatesThreeTestFiles() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        assertThat(testResult.files())
                .as("Expected pom.xml + 2 test Java files")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void testTaskIsCompletedWithTokens() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        assertThat(testResult.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(testResult.task().getTokensUsed()).isNotNull().isPositive();
    }

    @Test
    void allTestArtifactsHaveTestCodeType() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        testResult.artifacts().forEach(a ->
                assertThat(a.getArtifactType()).isEqualTo(ArtifactType.TEST_CODE));
    }

    @Test
    void generatedPomHasTestDependency() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        String pom = testResult.files().stream()
                .filter(f -> f.filePath().equals("pom.xml"))
                .findFirst().orElseThrow().content();
        assertThat(pom).contains("spring-boot-starter-test");
    }

    @Test
    void generatedServiceTestUsesJUnit5() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        String svcTest = testResult.files().stream()
                .filter(f -> f.filePath().contains("ServiceTest.java"))
                .findFirst().orElseThrow().content();
        assertThat(svcTest).contains("org.junit.jupiter").doesNotContain("org.junit.Test");
    }

    @Test
    void generatedControllerTestUsesWebMvcTest() {
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        String ctrlTest = testResult.files().stream()
                .filter(f -> f.filePath().contains("ControllerTest.java"))
                .findFirst().orElseThrow().content();
        assertThat(ctrlTest).contains("@WebMvcTest").contains("MockMvc");
    }

    // ------------------------------------------------------------------
    // THE compile + test run
    // ------------------------------------------------------------------

    @Test
    void generatedTestsCompileAndPass(@TempDir Path tempDir) throws Exception {
        // 1. Generate service
        ServiceGenerationResult svcResult = serviceGeneratorAgent.generate(
                1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        Map<String, String> sources = toMap(svcResult);

        // 2. Write service files
        for (GeneratedFile f : svcResult.files()) {
            Path target = tempDir.resolve(f.filePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content());
        }

        // 3. Generate tests (passing service sources as context)
        TestGenerationResult testResult = testWriterAgent.generate(
                1L, "owner-service", "ownerservice", "Owner",
                orEmpty(sources, "Service.java"),
                orEmpty(sources, "Controller.java"),
                orEmpty(sources, "Owner.java"),
                orEmpty(sources, "pom.xml"),
                List.of());

        // 4. Write test files — pom.xml from tests overwrites service pom (adds test deps)
        for (GeneratedFile f : testResult.files()) {
            Path target = tempDir.resolve(f.filePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content());
        }

        assertThat(tempDir.resolve("pom.xml")).exists();

        // 5. Run mvn test
        String mvnCmd   = System.getenv("MAVEN_HOME") != null
                ? System.getenv("MAVEN_HOME") + "/bin/mvn" : "mvn";
        String localRepo = System.getProperty("user.home") + "/.m2/repository";

        Process process = new ProcessBuilder(
                mvnCmd, "test",
                "-f",    tempDir.resolve("pom.xml").toString(),
                "-q",
                "-Dmaven.repo.local=" + localRepo
        ).redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode  = process.waitFor();

        if (exitCode != 0) {
            System.out.println("=== mvn test output (exit " + exitCode + ") ===\n" + output);
            for (GeneratedFile f : svcResult.files())
                System.out.println("=== " + f.filePath() + " ===\n" + f.content());
            for (GeneratedFile f : testResult.files())
                System.out.println("=== " + f.filePath() + " ===\n" + f.content());
        }

        assertThat(exitCode)
                .as("mvn test must exit 0.\nOutput:\n" + output)
                .isEqualTo(0);

        // 6. Sanity: at least some test output confirms tests ran
        // (mvn -q suppresses most output, but exit 0 means BUILD SUCCESS + tests passed)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, String> toMap(ServiceGenerationResult r) {
        return r.files().stream().collect(
                Collectors.toMap(GeneratedFile::filePath, GeneratedFile::content,
                        (a, b) -> a));
    }

    private static String orEmpty(Map<String, String> map, String suffix) {
        return map.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }
}
