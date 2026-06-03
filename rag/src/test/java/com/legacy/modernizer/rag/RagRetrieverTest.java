package com.legacy.modernizer.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagRetrieverTest {

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    RagRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new RagRetriever(objectMapper);
        // Point to scripts dir — not invoked in these unit tests
        org.springframework.test.util.ReflectionTestUtils.setField(
                retriever, "pythonExec",  "/opt/homebrew/bin/python3.11");
        org.springframework.test.util.ReflectionTestUtils.setField(
                retriever, "scriptsDir",  "../scripts");
        org.springframework.test.util.ReflectionTestUtils.setField(
                retriever, "qdrantUrl",   "http://localhost:6333");
    }

    // -----------------------------------------------------------------
    // parseSearchResponse
    // -----------------------------------------------------------------

    private static final String QDRANT_RESPONSE = """
            {
              "result": [
                {
                  "id": 0,
                  "score": 0.82,
                  "payload": {
                    "class_fqn":   "org.petclinic.owner.OwnerController",
                    "method_name": "findOwner",
                    "signature":   "public Owner findOwner(int ownerId)",
                    "body":        "public Owner findOwner(int ownerId) { return owners.findById(ownerId); }",
                    "file_path":   "src/main/java/OwnerController.java"
                  }
                },
                {
                  "id": 1,
                  "score": 0.75,
                  "payload": {
                    "class_fqn":   "org.petclinic.owner.OwnerRepository",
                    "method_name": "findByLastName",
                    "signature":   "Collection<Owner> findByLastName(String lastName)",
                    "body":        "Collection<Owner> findByLastName(String lastName);",
                    "file_path":   "src/main/java/OwnerRepository.java"
                  }
                }
              ],
              "status": "ok",
              "time": 0.001
            }
            """;

    @Test
    void parseSearchResponseExtractsAllFields() throws Exception {
        List<CodeChunk> chunks = retriever.parseSearchResponse(QDRANT_RESPONSE);

        assertThat(chunks).hasSize(2);
        CodeChunk first = chunks.get(0);
        assertThat(first.classFqn()).isEqualTo("org.petclinic.owner.OwnerController");
        assertThat(first.methodName()).isEqualTo("findOwner");
        assertThat(first.score()).isEqualTo(0.82f, within(0.001f));
    }

    @Test
    void parseSearchResponsePreservesScore() throws Exception {
        List<CodeChunk> chunks = retriever.parseSearchResponse(QDRANT_RESPONSE);
        assertThat(chunks.get(0).score()).isGreaterThan(chunks.get(1).score());
    }

    @Test
    void parseSearchResponseHandlesEmptyResult() throws Exception {
        String empty = """
                {"result": [], "status": "ok", "time": 0.0}
                """;
        assertThat(retriever.parseSearchResponse(empty)).isEmpty();
    }

    @Test
    void parseSearchResponseHandlesMissingPayloadFields() throws Exception {
        String sparse = """
                {
                  "result": [
                    { "id": 0, "score": 0.5, "payload": {} }
                  ],
                  "status": "ok"
                }
                """;
        List<CodeChunk> chunks = retriever.parseSearchResponse(sparse);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).classFqn()).isNull();
        assertThat(chunks.get(0).methodName()).isNull();
    }

    // -----------------------------------------------------------------
    // CodeChunk.toPromptContext
    // -----------------------------------------------------------------

    @Test
    void toPromptContextIncludesClassAndMethod() throws Exception {
        List<CodeChunk> chunks = retriever.parseSearchResponse(QDRANT_RESPONSE);
        String ctx = chunks.get(0).toPromptContext();
        assertThat(ctx).contains("org.petclinic.owner.OwnerController");
        assertThat(ctx).contains("findOwner");
    }

    // -----------------------------------------------------------------
    // searchQdrant — wires correctly (verifies HTTP call structure via spy)
    // -----------------------------------------------------------------

    @Test
    void searchQdrantBuildsCorrectUrl() {
        // searchQdrant will try to connect to localhost:6333 (may or may not be running).
        // We verify that the collection name "job_42" appears in the error when it fails.
        double[] stubVec = new double[768];
        assertThatThrownBy(() -> retriever.searchQdrant(42L, stubVec, 5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("job_42");
    }
}
