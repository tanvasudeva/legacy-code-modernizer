package com.legacy.modernizer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.agent.dto.ServiceBoundaryDto;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ArchitectAgentTest {

    // Realistic LLM response matching PetClinic structure
    private static final String MOCK_LLM_RESPONSE = """
            {
              "services": [
                {
                  "service_name": "owner-service",
                  "domain_context": "Manages pet owners, their personal data, and associated pets.",
                  "included_classes": [
                    "org.springframework.samples.petclinic.owner.Owner",
                    "org.springframework.samples.petclinic.owner.OwnerController",
                    "org.springframework.samples.petclinic.owner.OwnerRepository",
                    "org.springframework.samples.petclinic.owner.Pet",
                    "org.springframework.samples.petclinic.owner.PetController"
                  ],
                  "rationale": "Owner, Pet, and their controllers form a cohesive bounded context centred on the owner aggregate root."
                },
                {
                  "service_name": "vet-service",
                  "domain_context": "Manages veterinarians and their medical specialties.",
                  "included_classes": [
                    "org.springframework.samples.petclinic.vet.Vet",
                    "org.springframework.samples.petclinic.vet.VetController",
                    "org.springframework.samples.petclinic.vet.VetRepository",
                    "org.springframework.samples.petclinic.vet.Specialty",
                    "org.springframework.samples.petclinic.vet.Vets"
                  ],
                  "rationale": "Vet and Specialty classes form the vet domain with clear responsibility boundaries."
                },
                {
                  "service_name": "visit-service",
                  "domain_context": "Records and manages clinical visits between pets and vets.",
                  "included_classes": [
                    "org.springframework.samples.petclinic.owner.Visit",
                    "org.springframework.samples.petclinic.owner.VisitController"
                  ],
                  "rationale": "Visit management is a distinct transaction script with its own lifecycle."
                },
                {
                  "service_name": "model-service",
                  "domain_context": "Shared domain base entities used across bounded contexts.",
                  "included_classes": [
                    "org.springframework.samples.petclinic.model.BaseEntity",
                    "org.springframework.samples.petclinic.model.NamedEntity",
                    "org.springframework.samples.petclinic.model.Person"
                  ],
                  "rationale": "Shared kernel classes that provide common identity and naming conventions."
                },
                {
                  "service_name": "system-service",
                  "domain_context": "Infrastructure and cross-cutting concerns: caching, error handling, web config.",
                  "included_classes": [
                    "org.springframework.samples.petclinic.system.CacheConfiguration",
                    "org.springframework.samples.petclinic.system.CrashController",
                    "org.springframework.samples.petclinic.system.WelcomeController",
                    "org.springframework.samples.petclinic.system.WebConfiguration"
                  ],
                  "rationale": "System-level classes with no domain responsibility belong in a single infrastructure service."
                }
              ]
            }
            """;

    private static final Map<String, String> PETCLINIC_CLUSTER_MAP = Map.ofEntries(
            Map.entry("org.springframework.samples.petclinic.owner.Owner",           "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.OwnerController", "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.OwnerRepository", "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.Pet",             "owner-service"),
            Map.entry("org.springframework.samples.petclinic.owner.PetController",   "owner-service"),
            Map.entry("org.springframework.samples.petclinic.vet.Vet",              "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.VetController",    "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.VetRepository",    "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.Specialty",        "vet-service"),
            Map.entry("org.springframework.samples.petclinic.vet.Vets",            "vet-service"),
            Map.entry("org.springframework.samples.petclinic.owner.Visit",          "visit-service"),
            Map.entry("org.springframework.samples.petclinic.owner.VisitController","visit-service"),
            Map.entry("org.springframework.samples.petclinic.model.BaseEntity",     "model-service"),
            Map.entry("org.springframework.samples.petclinic.model.NamedEntity",    "model-service"),
            Map.entry("org.springframework.samples.petclinic.model.Person",         "model-service"),
            Map.entry("org.springframework.samples.petclinic.system.CacheConfiguration","system-service"),
            Map.entry("org.springframework.samples.petclinic.system.CrashController",   "system-service"),
            Map.entry("org.springframework.samples.petclinic.system.WelcomeController", "system-service"),
            Map.entry("org.springframework.samples.petclinic.system.WebConfiguration",  "system-service")
    );

    private ArchitectAgent agent;
    private ServiceBoundaryRepository repository;

    @BeforeEach
    void setUp() {
        ChatLanguageModel mockLlm = mock(ChatLanguageModel.class);
        when(mockLlm.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from(MOCK_LLM_RESPONSE)));

        repository = mock(ServiceBoundaryRepository.class);
        when(repository.save(any(ServiceBoundary.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        agent = new ArchitectAgent(mockLlm, repository, new ObjectMapper());
    }

    @Test
    void produces4to7ServiceBoundaries() {
        List<ServiceBoundary> result = agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of());
        assertTrue(result.size() >= 4 && result.size() <= 7,
                "Expected 4–7 boundaries, got " + result.size());
    }

    @Test
    void allBoundariesHaveNonBlankServiceName() {
        agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of()).forEach(b ->
                assertFalse(b.getServiceName() == null || b.getServiceName().isBlank()));
    }

    @Test
    void allBoundariesHaveRationale() {
        agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of()).forEach(b ->
                assertNotNull(b.getRationale(), "Rationale missing for: " + b.getServiceName()));
    }

    @Test
    void allBoundariesHaveDomainContext() {
        agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of()).forEach(b ->
                assertNotNull(b.getDescription(), "Description missing for: " + b.getServiceName()));
    }

    @Test
    void allBoundariesHaveAtLeastOneClass() {
        agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of()).forEach(b ->
                assertFalse(b.getClassFqns().isEmpty(), b.getServiceName() + " has no classes"));
    }

    @Test
    void eachBoundaryPersistedToRepository() {
        List<ServiceBoundary> result = agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of());
        verify(repository, times(result.size())).save(any(ServiceBoundary.class));
    }

    @Test
    void serviceNamesAreKebabCase() {
        agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of()).forEach(b ->
                assertTrue(b.getServiceName().matches("[a-z][a-z0-9-]+"),
                        "Not kebab-case: " + b.getServiceName()));
    }

    @Test
    void knownServiceNamesPresent() {
        List<String> names = agent.analyze(1L, PETCLINIC_CLUSTER_MAP, Map.of())
                .stream().map(ServiceBoundary::getServiceName).toList();
        assertTrue(names.contains("owner-service"), "owner-service expected");
        assertTrue(names.contains("vet-service"),   "vet-service expected");
    }
}
