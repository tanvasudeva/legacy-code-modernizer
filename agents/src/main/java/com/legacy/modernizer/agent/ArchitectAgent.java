package com.legacy.modernizer.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.agent.dto.ServiceBoundaryDto;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Architect agent: converts a Louvain cluster map into DDD-aligned service boundaries.
 *
 * <p>Flow:
 * <ol>
 *   <li>Groups the cluster map (className → serviceName) by service.</li>
 *   <li>Builds a structured LLM prompt applying DDD bounded-context principles.</li>
 *   <li>Calls {@link ChatLanguageModel} (Claude sonnet-4-6 or Ollama fallback).</li>
 *   <li>Parses and validates the JSON response.</li>
 *   <li>Persists each boundary to {@code service_boundaries} via JPA.</li>
 * </ol>
 */
@Component
public class ArchitectAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectAgent.class);

    // -----------------------------------------------------------------
    // Prompts
    // -----------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a senior software architect specialising in Domain-Driven Design (DDD) \
            and microservice decomposition of legacy monolithic Java applications.

            Given a cluster analysis of a Java codebase produced by Louvain community detection, \
            your task is to produce a formal microservice decomposition following DDD principles:

            1. Identify natural BOUNDED CONTEXTS — each service owns its domain data and logic.
            2. Apply UBIQUITOUS LANGUAGE — service names reflect the domain vocabulary.
            3. Ensure HIGH COHESION — every class in a service serves the same domain capability.
            4. Minimise COUPLING — cross-service dependencies are explicit, not implicit.
            5. Merge clusters with fewer than 2 domain classes into the nearest semantic service.

            Output ONLY valid JSON in this exact schema — no markdown, no explanation, no prose:
            {
              "services": [
                {
                  "service_name": "<lowercase-kebab-case-ending-in-service>",
                  "domain_context": "<2-3 sentence description of the bounded context and its primary responsibility>",
                  "included_classes": ["fully.qualified.ClassName"],
                  "rationale": "<why these classes form a cohesive DDD bounded context>"
                }
              ]
            }

            Constraints:
            - service_name must be lowercase kebab-case (e.g., "owner-service", "vet-service")
            - Every class in the input MUST appear in exactly one service's included_classes
            - Produce between 3 and 8 services
            - Output ONLY the JSON object
            """;

    // -----------------------------------------------------------------

    private final ChatLanguageModel chatModel;
    private final ServiceBoundaryRepository boundaryRepository;
    private final ObjectMapper objectMapper;

    public ArchitectAgent(ChatLanguageModel chatModel,
                          ServiceBoundaryRepository boundaryRepository,
                          ObjectMapper objectMapper) {
        this.chatModel          = chatModel;
        this.boundaryRepository = boundaryRepository;
        this.objectMapper       = objectMapper;
    }

    /**
     * Analyses the cluster map, calls the LLM, and persists the resulting
     * {@link ServiceBoundary} rows for the given job.
     *
     * @param jobId      the migration job this analysis belongs to
     * @param clusterMap Louvain output: fully-qualified class name → proposed service name
     * @return persisted {@link ServiceBoundary} list (one per proposed microservice)
     */
    public List<ServiceBoundary> analyze(Long jobId, Map<String, String> clusterMap) {
        log.info("[architect] Analysing {} classes across {} clusters for job {}",
                clusterMap.size(), new HashSet<>(clusterMap.values()).size(), jobId);

        // 1. Group by cluster name
        Map<String, List<String>> grouped = groupByService(clusterMap);

        // 2. Build user prompt
        String userPrompt = buildUserPrompt(grouped);
        log.debug("[architect] Prompt:\n{}", userPrompt);

        // 3. Call LLM
        String rawResponse = chatModel.generate(
                List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
        ).content().text();
        log.debug("[architect] LLM raw response:\n{}", rawResponse);

        // 4. Parse JSON
        List<ServiceBoundaryDto> boundaries = parseResponse(rawResponse);
        log.info("[architect] Parsed {} service boundaries", boundaries.size());

        // 5. Validate
        validate(boundaries, clusterMap);

        // 6. Delete any previous boundaries for this job, then persist
        boundaryRepository.deleteByJobId(jobId);
        return boundaries.stream()
                .map(dto -> persist(jobId, dto))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Map<String, List<String>> groupByService(Map<String, String> clusterMap) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        clusterMap.forEach((fqn, svc) ->
                grouped.computeIfAbsent(svc, k -> new ArrayList<>()).add(fqn));
        return grouped;
    }

    private String buildUserPrompt(Map<String, List<String>> grouped) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cluster map from Louvain community detection:\n\n");
        grouped.forEach((svc, classes) -> {
            sb.append("Cluster \"").append(svc).append("\" (").append(classes.size()).append(" classes):\n");
            classes.forEach(c -> sb.append("  • ").append(c).append("\n"));
            sb.append("\n");
        });
        int total = grouped.values().stream().mapToInt(List::size).sum();
        sb.append("Total: ").append(total).append(" classes across ").append(grouped.size()).append(" clusters.\n\n");
        sb.append("Produce the microservice decomposition JSON.");
        return sb.toString();
    }

    private List<ServiceBoundaryDto> parseResponse(String raw) {
        try {
            String json = extractJson(raw);
            ArchitectResponse response = objectMapper.readValue(json, ArchitectResponse.class);
            if (response.services() == null || response.services().isEmpty()) {
                throw new IllegalStateException("LLM returned empty services list");
            }
            return response.services();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response as JSON: " + e.getMessage()
                    + "\nRaw response:\n" + raw, e);
        }
    }

    /** Strips markdown code fences and extracts the outermost JSON object. */
    private String extractJson(String raw) {
        String s = raw.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        return (start != -1 && end > start) ? s.substring(start, end + 1) : s;
    }

    private void validate(List<ServiceBoundaryDto> services, Map<String, String> input) {
        Set<String> outputClasses = new HashSet<>();
        for (ServiceBoundaryDto svc : services) {
            if (svc.serviceName() == null || !svc.serviceName().matches("[a-z][a-z0-9-]+")) {
                log.warn("[architect] Non-standard service name: '{}'", svc.serviceName());
            }
            if (svc.includedClasses() != null) {
                outputClasses.addAll(svc.includedClasses());
            }
        }
        Set<String> missing = new HashSet<>(input.keySet());
        missing.removeAll(outputClasses);
        if (!missing.isEmpty()) {
            log.warn("[architect] {} input classes absent from LLM output: {}", missing.size(), missing);
        }
    }

    private ServiceBoundary persist(Long jobId, ServiceBoundaryDto dto) {
        ServiceBoundary entity = ServiceBoundary.builder()
                .jobId(jobId)
                .serviceName(dto.serviceName())
                .classFqns(dto.includedClasses() != null ? dto.includedClasses() : List.of())
                .description(dto.domainContext())
                .rationale(dto.rationale())
                .build();
        ServiceBoundary saved = boundaryRepository.save(entity);
        log.debug("[architect] Persisted: {} ({} classes)", saved.getServiceName(),
                saved.getClassFqns().size());
        return saved;
    }

    // -----------------------------------------------------------------
    // Internal deserialization wrapper
    // -----------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ArchitectResponse(
            @JsonProperty("services") List<ServiceBoundaryDto> services
    ) {}
}
