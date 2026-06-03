package com.legacy.modernizer.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** LLM response schema for a single proposed microservice boundary. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceBoundaryDto(
        @JsonProperty("service_name")    String serviceName,
        @JsonProperty("domain_context")  String domainContext,
        @JsonProperty("included_classes") List<String> includedClasses,
        @JsonProperty("rationale")       String rationale
) {}
