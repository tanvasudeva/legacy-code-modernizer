package com.legacy.modernizer.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single method-level code chunk returned by {@link RagRetriever}.
 * Populated from the Qdrant point payload + search score.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CodeChunk(
        @JsonProperty("class_fqn")   String classFqn,
        @JsonProperty("method_name") String methodName,
        @JsonProperty("signature")   String signature,
        @JsonProperty("body")        String body,
        @JsonProperty("file_path")   String filePath,
        float score
) {
    /** Compact summary for use in LLM prompts. */
    public String toPromptContext() {
        return "// %s#%s\n%s".formatted(classFqn, methodName, body);
    }
}
