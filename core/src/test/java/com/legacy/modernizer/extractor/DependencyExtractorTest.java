package com.legacy.modernizer.extractor;

import com.legacy.modernizer.model.ClassNode;
import com.legacy.modernizer.model.DependencyGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DependencyExtractorTest {

    private static DependencyGraph graph;

    @BeforeAll
    static void extractPetclinic() {
        Path petclinicSrc = Path.of("").toAbsolutePath()
                .getParent()
                .resolve("benchmarks/spring-petclinic/src");
        graph = new DependencyExtractor().extract(petclinicSrc);
    }

    @Test
    void extractsMoreThan40ClassNodes() {
        assertTrue(graph.getNodes().size() > 40,
                "Expected > 40 class nodes, got " + graph.getNodes().size());
    }

    @Test
    void allNodesHaveFqn() {
        for (ClassNode node : graph.getNodes()) {
            assertNotNull(node.getFullyQualifiedName(), "Missing FQN on: " + node.getClassName());
            assertFalse(node.getFullyQualifiedName().isBlank(), "Blank FQN on: " + node.getClassName());
        }
    }

    @Test
    void allNodesHaveClassName() {
        for (ClassNode node : graph.getNodes()) {
            assertNotNull(node.getClassName());
            assertFalse(node.getClassName().isBlank());
        }
    }

    @Test
    void callEdgesAreExtracted() {
        assertFalse(graph.getEdges().isEmpty(), "Expected at least one call edge");
    }

    @Test
    void knownPetclinicClassesArePresent() {
        boolean foundOwner = graph.getNodes().stream().anyMatch(n -> "Owner".equals(n.getClassName()));
        boolean foundVet   = graph.getNodes().stream().anyMatch(n -> "Vet".equals(n.getClassName()));
        assertTrue(foundOwner, "Expected to find Owner class");
        assertTrue(foundVet,   "Expected to find Vet class");
    }
}
