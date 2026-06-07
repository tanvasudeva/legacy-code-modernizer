package com.legacy.modernizer.stream.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.agent.DocGenAgent;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import com.legacy.modernizer.stream.AbstractStreamConsumer;
import com.legacy.modernizer.stream.AgentTaskMessage;
import com.legacy.modernizer.stream.StreamKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Consumes DOC_GEN tasks from {@value StreamKeys#DOC_GENERATOR}.
 *
 * <p>Expected payload JSON: {@code {"boundaryId":"<id>"}}.
 * Looks up the {@link ServiceBoundary} and its {@code SERVICE_CODE} artifacts from DB,
 * then delegates to {@link DocGenAgent}.
 */
@Component
public class DocGeneratorConsumer extends AbstractStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocGeneratorConsumer.class);

    private final DocGenAgent               docGenAgent;
    private final ServiceBoundaryRepository boundaryRepository;
    private final ArtifactRepository        artifactRepository;
    private final ObjectMapper              objectMapper;

    public DocGeneratorConsumer(StringRedisTemplate redisTemplate,
                                DocGenAgent docGenAgent,
                                ServiceBoundaryRepository boundaryRepository,
                                ArtifactRepository artifactRepository,
                                ObjectMapper objectMapper) {
        super(redisTemplate, StreamKeys.DOC_GENERATOR, "doc-generator-consumer-1");
        this.docGenAgent        = docGenAgent;
        this.boundaryRepository = boundaryRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper       = objectMapper;
    }

    @Override
    protected void process(AgentTaskMessage message) {
        log.info("[doc-generator] Received DOC_GEN for {} (job {})",
                message.classFqn(), message.jobId());

        DocPayload payload = parsePayload(message);
        Long jobId         = Long.parseLong(message.jobId());
        Long boundaryId    = Long.parseLong(payload.boundaryId());

        ServiceBoundary boundary = boundaryRepository.findById(boundaryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "ServiceBoundary not found: " + boundaryId));

        String serviceName = boundary.getServiceName();
        List<Artifact> serviceArtifacts = artifactRepository
                .findByJobIdAndClassFqn(jobId, serviceName)
                .stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .toList();

        String pkg        = serviceName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String entityName = serviceArtifacts.stream()
                .map(Artifact::getFilePath)
                .filter(p -> p != null && p.contains("/controller/") && p.endsWith("Controller.java"))
                .map(p -> p.substring(p.lastIndexOf('/') + 1).replace("Controller.java", ""))
                .findFirst().orElse("Entity");

        docGenAgent.generate(
                jobId, boundary, pkg, entityName,
                findContent(serviceArtifacts, "Controller.java"),
                findContent(serviceArtifacts, "Service.java"),
                findContent(serviceArtifacts, "entity/" + entityName + ".java"),
                List.of()
        );
    }

    private DocPayload parsePayload(AgentTaskMessage message) {
        String raw = message.payload();
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException("Missing payload for DOC_GEN on " + message.classFqn());
        try {
            return objectMapper.readValue(raw, DocPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DOC_GEN payload: " + e.getMessage(), e);
        }
    }

    private static String findContent(List<Artifact> artifacts, String suffix) {
        return artifacts.stream()
                .filter(a -> a.getFilePath() != null && a.getFilePath().endsWith(suffix))
                .map(Artifact::getContent)
                .findFirst().orElse("");
    }

    record DocPayload(String boundaryId) {}
}
