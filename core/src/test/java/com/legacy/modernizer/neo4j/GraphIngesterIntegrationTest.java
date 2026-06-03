package com.legacy.modernizer.neo4j;

import com.legacy.modernizer.extractor.DependencyExtractor;
import com.legacy.modernizer.model.DependencyGraph;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphIngesterIntegrationTest {

    private static Driver        driver;
    private static GraphIngester ingester;
    private static IngestionStats stats;

    @BeforeAll
    static void setUp() {
        driver   = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "neo4j_password"));
        ingester = new GraphIngester(driver);

        try (Session s = driver.session()) {
            s.run("MATCH (n:Class)   DETACH DELETE n");
            s.run("MATCH (n:Package) DETACH DELETE n");
        }
        ingester.initSchema();

        Path petclinicSrc = Path.of("").toAbsolutePath().getParent()
                .resolve("benchmarks/spring-petclinic/src");
        DependencyGraph graph = new DependencyExtractor().extract(petclinicSrc);
        stats = ingester.ingest(graph);
    }

    @AfterAll
    static void tearDown() { if (driver != null) driver.close(); }

    @Test @Order(1)
    void classNodeCountIsWithinExpectedRange() {
        try (Session s = driver.session()) {
            long count = s.run("MATCH (c:Class) RETURN count(c) AS n").single().get("n").asLong();
            assertTrue(count >= 30 && count <= 60, "Expected 30–60 Class nodes, got " + count);
        }
    }

    @Test @Order(2)
    void packageNodesAreCreated() {
        try (Session s = driver.session()) {
            long count = s.run("MATCH (p:Package) RETURN count(p) AS n").single().get("n").asLong();
            assertTrue(count >= 3, "Expected >= 3 Package nodes, got " + count);
        }
    }

    @Test @Order(3)
    void relationshipCountExceedsThreshold() {
        try (Session s = driver.session()) {
            long count = s.run("MATCH ()-[r]->() RETURN count(r) AS n").single().get("n").asLong();
            assertTrue(count >= 50, "Expected >= 50 total relationships, got " + count);
        }
    }

    @Test @Order(4)
    void extendsRelationshipsExist() {
        try (Session s = driver.session()) {
            long count = s.run("MATCH ()-[:EXTENDS]->() RETURN count(*) AS n").single().get("n").asLong();
            assertTrue(count >= 1, "Expected at least 1 EXTENDS edge");
        }
    }

    @Test @Order(5)
    void importsRelationshipsExist() {
        try (Session s = driver.session()) {
            long count = s.run("MATCH ()-[:IMPORTS]->() RETURN count(*) AS n").single().get("n").asLong();
            assertTrue(count >= 5, "Expected >= 5 IMPORTS edges");
        }
    }

    @Test @Order(6)
    void ingestionStatsAreConsistent() {
        assertNotNull(stats);
        assertTrue(stats.classNodes() >= 30, "Expected >= 30 class nodes");
        assertTrue(stats.relationships() >= 10, "Expected >= 10 relationships");
    }

    @Test @Order(7)
    void ownerAndVetNodesExist() {
        try (Session s = driver.session()) {
            long ownerCount = s.run("MATCH (c:Class {name: 'Owner'}) RETURN count(c) AS n").single().get("n").asLong();
            long vetCount   = s.run("MATCH (c:Class {name: 'Vet'})   RETURN count(c) AS n").single().get("n").asLong();
            assertEquals(1, ownerCount, "Expected exactly 1 Owner node");
            assertEquals(1, vetCount,   "Expected exactly 1 Vet node");
        }
    }

    @Test @Order(8)
    void adjacencyListJsonIsNonEmpty() {
        String json = ingester.exportAdjacencyListJson();
        assertNotNull(json);
        assertTrue(json.startsWith("["));
        assertTrue(json.contains("source") && json.contains("target") && json.contains("type"));
    }
}
