package com.legacy.modernizer.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test: indexes PetClinic into Qdrant, then queries for
 * "payment processing logic" and verifies retrieval returns top-5 chunks.
 *
 * <p>Skipped automatically when Qdrant is not reachable on localhost:6333.
 * Run with:  mvn test -pl rag -Dtest=RagIndexIntegrationTest
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "qdrant.url=http://localhost:6333"
})
class RagIndexIntegrationTest {

    private static final String PETCLINIC_SOURCE =
            "benchmarks/spring-petclinic/src/main/java";

    private static final long TEST_JOB_ID = 9999L;

    @Autowired RagIndexService  ragIndexService;
    @Autowired RagRetriever     ragRetriever;

    // -----------------------------------------------------------------
    // Guard: skip if Qdrant is not running
    // -----------------------------------------------------------------

    @BeforeAll
    static void assumeQdrantRunning() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req   = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:6333/"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString());
            assumeTrue(resp.statusCode() == 200, "Qdrant not reachable on :6333");
        } catch (Exception e) {
            assumeTrue(false, "Qdrant not reachable: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Indexing
    // -----------------------------------------------------------------

    @Test
    void indexesPetClinicSuccessfully() throws Exception {
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        int count = ragIndexService.index(TEST_JOB_ID, absSource);

        assertThat(count).isGreaterThan(0);
        // PetClinic has 30 files with ~79 methods
        assertThat(count).isGreaterThanOrEqualTo(50);
    }

    // -----------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------

    @Test
    void retrievalReturnsTopFiveChunks() throws Exception {
        // Ensure indexed
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        ragIndexService.index(TEST_JOB_ID, absSource);

        List<CodeChunk> chunks = ragRetriever.retrieve(
                TEST_JOB_ID, "payment processing logic", 5);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void retrievedChunksHaveRequiredFields() throws Exception {
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        ragIndexService.index(TEST_JOB_ID, absSource);

        List<CodeChunk> chunks = ragRetriever.retrieve(
                TEST_JOB_ID, "payment processing logic", 5);

        for (CodeChunk chunk : chunks) {
            assertThat(chunk.classFqn()).isNotBlank();
            assertThat(chunk.methodName()).isNotBlank();
            assertThat(chunk.body()).isNotBlank();
            assertThat(chunk.score()).isGreaterThanOrEqualTo(0f);
        }
    }

    @Test
    void retrievalScoresAreDescending() throws Exception {
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        ragIndexService.index(TEST_JOB_ID, absSource);

        List<CodeChunk> chunks = ragRetriever.retrieve(
                TEST_JOB_ID, "payment processing logic", 5);

        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i - 1).score())
                    .isGreaterThanOrEqualTo(chunks.get(i).score());
        }
    }

    @Test
    void domainQueryReturnsDomainRelevantMethod() throws Exception {
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        ragIndexService.index(TEST_JOB_ID, absSource);

        // "find owner by last name" is directly represented in PetClinic
        List<CodeChunk> chunks = ragRetriever.retrieve(
                TEST_JOB_ID, "find owner by last name", 5);

        assertThat(chunks).isNotEmpty();
        boolean anyOwnerRelated = chunks.stream()
                .anyMatch(c -> c.classFqn() != null && c.classFqn().toLowerCase().contains("owner"));
        assertThat(anyOwnerRelated)
                .as("Expected at least one owner-related method in results")
                .isTrue();
    }

    @Test
    void toPromptContextProducesNonEmptyOutput() throws Exception {
        String absSource = Paths.get(PETCLINIC_SOURCE).toAbsolutePath().toString();
        ragIndexService.index(TEST_JOB_ID, absSource);

        List<CodeChunk> chunks = ragRetriever.retrieve(
                TEST_JOB_ID, "show vet specialties", 3);

        for (CodeChunk c : chunks) {
            assertThat(c.toPromptContext()).isNotBlank();
        }
    }
}
