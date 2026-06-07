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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: calls real Claude + Postgres, generates the "owner" service,
 * writes files to a temp dir, and asserts {@code mvn compile} exits 0.
 *
 * Skipped automatically unless ANTHROPIC_API_KEY is set.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ServiceGeneratorIntegrationTest {

    private static final ServiceBoundary OWNER_BOUNDARY = ServiceBoundary.builder()
            .id(null)
            .jobId(1L)
            .serviceName("owner-service")
            .description("Manages owner registration, lookup, and profile management "
                    + "within the owner bounded context.")
            .rationale("The Owner entity, its controller, and repository form a cohesive "
                    + "CRUD bounded context with no cross-domain dependencies.")
            .classFqns(List.of(
                    "org.springframework.samples.petclinic.owner.Owner",
                    "org.springframework.samples.petclinic.owner.OwnerController",
                    "org.springframework.samples.petclinic.owner.OwnerRepository"
            ))
            .build();

    // Minimal RAG context — representative PetClinic snippets
    private static final List<String> PETCLINIC_SNIPPETS = List.of(
            """
            @Entity
            @Table(name = "owners")
            public class Owner extends Person {
                @Column(name = "address") private String address;
                @Column(name = "city")    private String city;
                @Column(name = "telephone") private String telephone;
            }
            """,
            """
            @Repository
            public interface OwnerRepository extends Repository<Owner, Integer> {
                @Query("SELECT DISTINCT owner FROM Owner owner left join fetch owner.pets")
                Collection<Owner> findByLastNameStartingWith(String lastName, Pageable pageable);
                Owner findById(int id);
                void save(Owner owner);
            }
            """,
            """
            @Controller
            public class OwnerController {
                private final OwnerRepository owners;
                public OwnerController(OwnerRepository owners) { this.owners = owners; }

                @GetMapping("/owners/{ownerId}")
                public ModelAndView showOwner(@PathVariable int ownerId) {
                    ModelAndView mav = new ModelAndView("owners/ownerDetails");
                    mav.addObject(this.owners.findById(ownerId));
                    return mav;
                }
            }
            """
    );

    @Autowired ServiceGeneratorAgent agent;

    // -----------------------------------------------------------------
    // Generation tests
    // -----------------------------------------------------------------

    @Test
    void generatesAtLeastSevenFiles() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        assertThat(result.files())
                .as("Expected at least 7 files (pom + 6 Java)")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    void generatesAPomXml() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        boolean hasPom = result.files().stream()
                .anyMatch(f -> f.filePath().equals("pom.xml"));
        assertThat(hasPom).isTrue();
    }

    @Test
    void pomXmlContainsSpringBootParent() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        String pomContent = result.files().stream()
                .filter(f -> f.filePath().equals("pom.xml"))
                .findFirst().orElseThrow().content();
        assertThat(pomContent).contains("spring-boot-starter-parent");
        assertThat(pomContent).contains("3.2.5");
    }

    @Test
    void allJavaFilesHaveCorrectPackage() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        result.files().stream()
                .filter(f -> f.filePath().endsWith(".java"))
                .forEach(f -> assertThat(f.content())
                        .as("File %s should declare ownerservice package", f.filePath())
                        .contains("com.modernized.ownerservice"));
    }

    @Test
    void generatesRepositoryExtendingJpaRepository() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        boolean hasRepo = result.files().stream()
                .filter(f -> f.filePath().contains("Repository"))
                .anyMatch(f -> f.content().contains("JpaRepository"));
        assertThat(hasRepo).as("Expected a JpaRepository interface").isTrue();
    }

    @Test
    void generatesRestController() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        boolean hasController = result.files().stream()
                .filter(f -> f.filePath().contains("Controller"))
                .anyMatch(f -> f.content().contains("@RestController"));
        assertThat(hasController).as("Expected a @RestController class").isTrue();
    }

    @Test
    void allArtifactsHaveServiceCodeType() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        result.artifacts().forEach(a ->
                assertThat(a.getArtifactType()).isEqualTo(ArtifactType.SERVICE_CODE));
    }

    @Test
    void taskIsCompletedAndStoredWithTokenUsage() {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);
        assertThat(result.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(result.task().getTokensUsed()).isNotNull().isPositive();
    }

    // -----------------------------------------------------------------
    // THE compile test — writes files, runs mvn compile
    // -----------------------------------------------------------------

    @Test
    void generatedOwnerServiceCompilesWithMaven(@TempDir Path tempDir) throws Exception {
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, PETCLINIC_SNIPPETS);

        assertThat(result.files()).as("Need files to write").isNotEmpty();

        // Write all generated files to the temp directory
        for (GeneratedFile f : result.files()) {
            Path target = tempDir.resolve(f.filePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, f.content());
        }

        // Ensure pom.xml is present
        Path pomPath = tempDir.resolve("pom.xml");
        assertThat(pomPath).exists();

        // Run: mvn compile -f pom.xml -q
        String mvnCmd   = System.getenv("MAVEN_HOME") != null
                ? System.getenv("MAVEN_HOME") + "/bin/mvn" : "mvn";
        String localRepo = System.getProperty("user.home") + "/.m2/repository";

        Process process = new ProcessBuilder(
                mvnCmd, "compile",
                "-f",    pomPath.toString(),
                "-q",
                "-Dmaven.repo.local=" + localRepo
        ).redirectErrorStream(true).start();

        // Drain output so the process doesn't block
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode  = process.waitFor();

        if (exitCode != 0) {
            System.out.println("=== mvn compile output (exit " + exitCode + ") ===\n" + output);
            // Print the generated files for debugging
            for (GeneratedFile f : result.files()) {
                System.out.println("=== " + f.filePath() + " ===\n" + f.content() + "\n");
            }
        }

        assertThat(exitCode)
                .as("mvn compile must exit 0. Output:\n" + output)
                .isEqualTo(0);
    }
}
