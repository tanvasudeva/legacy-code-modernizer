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

import org.springframework.transaction.annotation.Transactional;

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
            4. Minimise COUPLING — merge clusters that have many CALLS edges between them into the \
               same service; cross-service calls should represent true integration points only.
            5. Merge clusters with fewer than 2 domain classes into the nearest semantic service.

            Output ONLY valid JSON in this exact schema — no markdown, no explanation, no prose:
            {
              "services": [
                {
                  "service_name": "<lowercase-kebab-case-ending-in-service>",
                  "domain_context": "<2-3 sentence description of the bounded context and its primary responsibility>",
                  "included_clusters": ["<cluster-name-exactly-as-given-in-input>"],
                  "rationale": "<why these clusters form a cohesive DDD bounded context>"
                }
              ]
            }

            Constraints:
            - service_name must be lowercase kebab-case (e.g., "owner-service", "vet-service")
            - Every cluster name in the input MUST appear in exactly one service's included_clusters
            - Use cluster names EXACTLY as they appear in the input — do not invent or modify them
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
     * @param jobId              the migration job this analysis belongs to
     * @param clusterMap         Louvain output: fully-qualified class name → proposed service name
     * @param interClusterCalls  cross-cluster CALLS edge counts: fromCluster → toCluster → count
     * @return persisted {@link ServiceBoundary} list (one per proposed microservice)
     */
    @Transactional
    public List<ServiceBoundary> analyze(Long jobId, Map<String, String> clusterMap,
                                         Map<String, Map<String, Integer>> interClusterCalls) {
        log.info("[architect] Analysing {} classes across {} clusters for job {}",
                clusterMap.size(), new HashSet<>(clusterMap.values()).size(), jobId);

        // 1. Group by cluster name
        Map<String, List<String>> grouped = groupByService(clusterMap);

        // 2. Build user prompt
        String userPrompt = buildUserPrompt(grouped, interClusterCalls != null ? interClusterCalls : Map.of());
        log.debug("[architect] Prompt:\n{}", userPrompt);

        // 3. Call LLM
        String rawResponse = chatModel.generate(
                List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
        ).content().text();
        log.debug("[architect] LLM raw response:\n{}", rawResponse);

        // 4. Parse JSON
        List<ServiceBoundaryDto> boundaries = parseResponse(rawResponse);
        log.info("[architect] Parsed {} service boundaries", boundaries.size());

        // 5. Validate cluster names returned by LLM against known clusters
        validate(boundaries, grouped);

        // 6. Delete any previous boundaries for this job, then persist
        boundaryRepository.deleteByJobId(jobId);
        return boundaries.stream()
                .map(dto -> persist(jobId, dto, grouped))
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

    private String buildUserPrompt(Map<String, List<String>> grouped,
                                   Map<String, Map<String, Integer>> interClusterCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cluster map from Louvain community detection:\n\n");
        grouped.forEach((svc, classes) -> {
            sb.append("Cluster \"").append(svc).append("\" (").append(classes.size()).append(" classes):\n");
            // Show at most 5 representative simple names to keep the prompt compact.
            int limit = Math.min(classes.size(), 5);
            for (int i = 0; i < limit; i++) {
                String fqn = classes.get(i);
                String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                sb.append("  • ").append(simpleName).append("\n");
            }
            if (classes.size() > 5) {
                sb.append("  … and ").append(classes.size() - 5).append(" more\n");
            }
            sb.append("\n");
        });
        int total = grouped.values().stream().mapToInt(List::size).sum();
        sb.append("Total: ").append(total).append(" classes across ").append(grouped.size()).append(" clusters.\n\n");

        // Append cross-cluster call frequencies (sorted descending) so the LLM can
        // prefer merging clusters that heavily call each other into the same service.
        if (!interClusterCalls.isEmpty()) {
            record Edge(String from, String to, int count) {}
            List<Edge> edges = new ArrayList<>();
            interClusterCalls.forEach((from, targets) ->
                    targets.forEach((to, count) -> edges.add(new Edge(from, to, count))));
            edges.sort((a, b) -> Integer.compare(b.count(), a.count()));

            sb.append("Cross-cluster CALLS frequency (higher = more tightly coupled):\n");
            sb.append("RULE: clusters with many cross-calls SHOULD be in the SAME service to minimise inter-service coupling.\n");
            int limit = Math.min(edges.size(), 20);
            for (int i = 0; i < limit; i++) {
                Edge e = edges.get(i);
                sb.append("  ").append(e.from()).append(" → ").append(e.to())
                  .append(" : ").append(e.count()).append(" calls\n");
            }
            sb.append("\n");
        }

        sb.append("Produce the microservice decomposition JSON.");
        return sb.toString();
    }

    private List<ServiceBoundaryDto> parseResponse(String raw) {
        // First attempt: parse as-is
        try {
            String json = extractJson(raw);
            ArchitectResponse response = objectMapper.readValue(json, ArchitectResponse.class);
            if (response.services() != null && !response.services().isEmpty()) {
                return deduplicate(response.services());
            }
        } catch (Exception ignored) {
            // fall through to recovery
        }

        // Second attempt: recover truncated JSON by finding last complete service object
        try {
            String recovered = recoverTruncated(raw);
            ArchitectResponse response = objectMapper.readValue(recovered, ArchitectResponse.class);
            if (response.services() != null && !response.services().isEmpty()) {
                log.warn("[architect] Recovered {} services from truncated/looping LLM response",
                        response.services().size());
                return deduplicate(response.services());
            }
        } catch (Exception e2) {
            throw new RuntimeException("Failed to parse LLM response as JSON: " + e2.getMessage()
                    + "\nRaw response:\n" + raw, e2);
        }

        throw new RuntimeException("LLM returned empty services list\nRaw response:\n" + raw);
    }

    /** Deduplicates by service_name, keeping first occurrence (handles LLM repetition loops). */
    private List<ServiceBoundaryDto> deduplicate(List<ServiceBoundaryDto> services) {
        return services.stream()
                .collect(Collectors.toMap(
                        ServiceBoundaryDto::serviceName,
                        s -> s,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    /** Strips markdown fences and returns the outermost JSON object. */
    private String extractJson(String raw) {
        String s = raw.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        return (start != -1 && end > start) ? s.substring(start, end + 1) : s;
    }

    /**
     * Recovers a truncated JSON response by finding the last complete service object
     * (depth returns to 1 inside the services array) and closing the array + root object.
     * Also handles repetition loops by truncating after all unique entries.
     */
    private String recoverTruncated(String raw) {
        String s = raw.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        if (start == -1) throw new IllegalArgumentException("No JSON object found");
        String json = s.substring(start);

        int lastServiceClose = -1;
        int depth = 0;
        boolean inString = false;
        char[] chars = json.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 1) lastServiceClose = i; // closed a service-level object
            }
        }
        if (lastServiceClose < 0) throw new IllegalArgumentException("No complete service object found");
        return json.substring(0, lastServiceClose + 1) + "]}";
    }

    private void validate(List<ServiceBoundaryDto> services, Map<String, List<String>> grouped) {
        Set<String> outputClusters = new HashSet<>();
        for (ServiceBoundaryDto svc : services) {
            if (svc.serviceName() == null || !svc.serviceName().matches("[a-z][a-z0-9-]+")) {
                log.warn("[architect] Non-standard service name: '{}'", svc.serviceName());
            }
            if (svc.includedClusters() != null) {
                outputClusters.addAll(svc.includedClusters());
            }
        }
        Set<String> missing = new HashSet<>(grouped.keySet());
        missing.removeAll(outputClusters);
        if (!missing.isEmpty()) {
            log.warn("[architect] {} clusters absent from LLM output: {}", missing.size(), missing);
        }
    }

    private ServiceBoundary persist(Long jobId, ServiceBoundaryDto dto, Map<String, List<String>> grouped) {
        // Expand cluster names → full class FQNs using the known cluster map.
        List<String> classFqns;
        if (dto.includedClusters() != null && !dto.includedClusters().isEmpty()) {
            classFqns = dto.includedClusters().stream()
                    .flatMap(cluster -> grouped.getOrDefault(cluster, List.of()).stream())
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            // Fallback: LLM used the old schema (should not happen after prompt update)
            classFqns = dto.includedClasses() != null ? dto.includedClasses() : List.of();
        }
        ServiceBoundary entity = ServiceBoundary.builder()
                .jobId(jobId)
                .serviceName(dto.serviceName())
                .classFqns(classFqns)
                .description(dto.domainContext())
                .rationale(dto.rationale())
                .build();
        ServiceBoundary saved = boundaryRepository.save(entity);
        log.debug("[architect] Persisted: {} ({} classes from {} clusters)",
                saved.getServiceName(), saved.getClassFqns().size(),
                dto.includedClusters() != null ? dto.includedClusters().size() : 0);
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
