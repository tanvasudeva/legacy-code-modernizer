package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against real Claude (claude-sonnet-4-6) + PostgreSQL.
 * Skipped automatically when ANTHROPIC_API_KEY is absent.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class RefactorerAgentIntegrationTest {

    private static final String PETCLINIC_SOURCE = """
            package org.springframework.samples.petclinic.owner;

            import javax.validation.Valid;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Controller;
            import org.springframework.ui.Model;
            import org.springframework.validation.BindingResult;
            import org.springframework.web.bind.annotation.*;

            @Controller
            @RequestMapping("/owners")
            public class OwnerController {

                @Autowired
                private OwnerRepository owners;

                @GetMapping("/{ownerId}")
                public String showOwner(@PathVariable int ownerId, Model model) {
                    Owner owner = owners.findById(ownerId);
                    model.addAttribute("owner", owner);
                    return "owners/ownerDetails";
                }

                @PostMapping("/new")
                public String processCreationForm(@Valid Owner owner, BindingResult result) {
                    if (result.hasErrors()) return "owners/createOrUpdateOwnerForm";
                    owners.save(owner);
                    return "redirect:/owners/" + owner.getId();
                }
            }
            """;

    @Autowired RefactorerAgent agent;
    @Autowired AgentTaskRepository taskRepository;
    @Autowired ArtifactRepository artifactRepository;

    @Test
    void refactorsOwnerControllerToRestController() {
        RefactorResult result = agent.refactor(
                999L,
                "org.springframework.samples.petclinic.owner.OwnerController",
                PETCLINIC_SOURCE,
                "owner-service",
                "Manages owner registration, lookup, and profile updates within the owner bounded context.",
                "src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"
        );

        assertThat(result.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(result.original().getArtifactType()).isEqualTo(ArtifactType.ORIGINAL);
        assertThat(result.transformed().getArtifactType()).isEqualTo(ArtifactType.TRANSFORMED);
        assertThat(result.transformed().getContent()).isNotBlank();
    }

    @Test
    void transformedCodeReplacesJavaxWithJakarta() {
        RefactorResult result = agent.refactor(
                999L,
                "org.springframework.samples.petclinic.owner.OwnerController",
                PETCLINIC_SOURCE,
                "owner-service",
                "Manages owners within the owner bounded context.",
                null
        );

        String transformed = result.transformed().getContent();
        assertThat(transformed).doesNotContain("javax.validation");
        assertThat(transformed).contains("jakarta");
    }

    @Test
    void transformedCodeUsesConstructorInjection() {
        RefactorResult result = agent.refactor(
                999L,
                "org.springframework.samples.petclinic.owner.OwnerController",
                PETCLINIC_SOURCE,
                "owner-service",
                "Manages owners.",
                null
        );

        String transformed = result.transformed().getContent();
        assertThat(transformed).doesNotContain("@Autowired");
    }

    @Test
    void taskIsPersistableAndRetrievableByJobId() {
        RefactorResult result = agent.refactor(
                998L,
                "org.springframework.samples.petclinic.owner.OwnerController",
                PETCLINIC_SOURCE,
                "owner-service",
                "Manages owners.",
                null
        );

        assertThat(taskRepository.findByJobId(998L))
                .anyMatch(t -> t.getId().equals(result.task().getId()));
    }

    @Test
    void checksumDiffersForOriginalAndTransformed() {
        RefactorResult result = agent.refactor(
                997L,
                "org.springframework.samples.petclinic.owner.OwnerController",
                PETCLINIC_SOURCE,
                "owner-service",
                "Manages owners.",
                null
        );

        assertThat(result.original().getChecksum())
                .isNotEqualTo(result.transformed().getChecksum());
    }
}
