package com.legacy.modernizer.agent;

/**
 * A single file produced by {@link ServiceGeneratorAgent}.
 *
 * @param filePath path relative to the service module root (e.g. {@code pom.xml},
 *                 {@code src/main/java/com/modernized/ownerservice/entity/Owner.java})
 * @param content  complete file text, ready to write to disk
 */
public record GeneratedFile(String filePath, String content) {}
