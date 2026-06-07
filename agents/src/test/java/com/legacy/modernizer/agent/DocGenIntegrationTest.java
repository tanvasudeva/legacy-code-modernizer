package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests: calls real Claude, generates all 3 docs for the owner service,
 * and validates OpenAPI YAML with swagger-parser (zero parse errors required).
 *
 * Skipped automatically unless ANTHROPIC_API_KEY is set.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class DocGenIntegrationTest {

    // Canonical source files (same as verified Phase 3.1 template)
    private static final String CONTROLLER_SRC = """
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
                public OwnerController(OwnerService svc) { this.svc = svc; }
                @GetMapping           public List<Owner> list()                                    { return svc.findAll(); }
                @GetMapping("/{id}")  public ResponseEntity<Owner> get(@PathVariable Long id)     { return ResponseEntity.ok(svc.findById(id)); }
                @PostMapping          public ResponseEntity<Owner> create(@RequestBody OwnerRequest r) { return ResponseEntity.ok(svc.create(r)); }
                @PutMapping("/{id}")  public ResponseEntity<Owner> update(@PathVariable Long id, @RequestBody OwnerRequest r) { return ResponseEntity.ok(svc.update(id, r)); }
                @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) { svc.delete(id); return ResponseEntity.noContent().build(); }
            }
            """;

    private static final String SERVICE_SRC = """
            package com.modernized.ownerservice.service;
            import com.modernized.ownerservice.dto.OwnerRequest;
            import com.modernized.ownerservice.entity.Owner;
            import com.modernized.ownerservice.repository.OwnerRepository;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import java.util.List;
            @Service @Transactional
            public class OwnerService {
                private final OwnerRepository repo;
                public OwnerService(OwnerRepository repo) { this.repo = repo; }
                public List<Owner> findAll() { return repo.findAll(); }
                public Owner findById(Long id) { return repo.findById(id).orElseThrow(); }
                public Owner create(OwnerRequest req) { return repo.save(Owner.builder().firstName(req.getFirstName()).lastName(req.getLastName()).build()); }
                public Owner update(Long id, OwnerRequest req) { Owner o = findById(id); o.setFirstName(req.getFirstName()); return repo.save(o); }
                public void delete(Long id) { repo.deleteById(id); }
            }
            """;

    private static final String ENTITY_SRC = """
            package com.modernized.ownerservice.entity;
            import jakarta.persistence.*;
            import lombok.*;
            @Data @Builder @NoArgsConstructor @AllArgsConstructor
            @Entity @Table(name = "owners")
            public class Owner {
                @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
                private String firstName;
                private String lastName;
                private String address;
                private String city;
                private String telephone;
            }
            """;

    private static final ServiceBoundary OWNER_BOUNDARY = ServiceBoundary.builder()
            .id(null).jobId(1L).serviceName("owner-service")
            .description("Manages owner registration, lookup, and profile management within the owner bounded context.")
            .rationale("Owner entity, controller, and repository are cohesively coupled with no external domain dependencies.")
            .classFqns(List.of(
                    "org.springframework.samples.petclinic.owner.Owner",
                    "org.springframework.samples.petclinic.owner.OwnerController",
                    "org.springframework.samples.petclinic.owner.OwnerRepository"))
            .build();

    @Autowired DocGenAgent agent;

    // ------------------------------------------------------------------
    // Generation tests
    // ------------------------------------------------------------------

    @Test
    void generatesThreeDocuments() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        assertThat(r.files()).hasSize(3);
    }

    @Test
    void allThreeArtifactTypesPresent() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        List<ArtifactType> types = r.artifacts().stream()
                .map(a -> a.getArtifactType()).toList();
        assertThat(types).containsExactlyInAnyOrder(
                ArtifactType.OPENAPI_SPEC, ArtifactType.ADR, ArtifactType.RUNBOOK);
    }

    @Test
    void taskCompletedWithTokens() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        assertThat(r.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(r.task().getTokensUsed()).isNotNull().isPositive();
    }

    // ------------------------------------------------------------------
    // Content tests
    // ------------------------------------------------------------------

    @Test
    void adrContainsStatusAccepted() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        String adr = r.files().stream()
                .filter(f -> f.filePath().endsWith(".md") && f.filePath().contains("adr"))
                .findFirst().orElseThrow().content();
        assertThat(adr).containsIgnoringCase("accepted");
    }

    @Test
    void runbookHasPreConditionsSection() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        String runbook = r.files().stream()
                .filter(f -> f.filePath().contains("runbook"))
                .findFirst().orElseThrow().content();
        assertThat(runbook).containsIgnoringCase("pre-condition");
    }

    @Test
    void runbookHasRollbackSection() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        String runbook = r.files().stream()
                .filter(f -> f.filePath().contains("runbook"))
                .findFirst().orElseThrow().content();
        assertThat(runbook).containsIgnoringCase("rollback");
    }

    @Test
    void openApiContainsOwnersPath() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());
        String yaml = r.files().stream()
                .filter(f -> f.filePath().endsWith("openapi.yaml"))
                .findFirst().orElseThrow().content();
        assertThat(yaml).contains("/api/owners");
    }

    // ------------------------------------------------------------------
    // THE validation test — swagger-parser must report zero errors
    // ------------------------------------------------------------------

    @Test
    void generatedOpenApiYamlParsesWithZeroErrors() {
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                CONTROLLER_SRC, SERVICE_SRC, ENTITY_SRC, List.of());

        String yamlContent = r.files().stream()
                .filter(f -> f.filePath().endsWith("openapi.yaml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("openapi.yaml not found in generated docs"))
                .content();

        assertThat(yamlContent)
                .as("YAML content must not be blank").isNotBlank();

        // Parse with swagger-parser v3
        ParseOptions opts = new ParseOptions();
        opts.setResolve(false); // don't follow $ref to remote URLs
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(yamlContent, null, opts);

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            System.out.println("=== OpenAPI parse messages ===");
            result.getMessages().forEach(System.out::println);
            System.out.println("=== Generated YAML ===\n" + yamlContent);
        }

        assertThat(result.getOpenAPI())
                .as("Parsed OpenAPI object must not be null — YAML:\n" + yamlContent)
                .isNotNull();
        assertThat(result.getMessages())
                .as("swagger-parser must report zero errors")
                .isNullOrEmpty();
    }
}
