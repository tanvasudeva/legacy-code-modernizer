package com.legacy.modernizer.sharedlib;

import com.legacy.modernizer.model.ServiceBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test for SharedLibraryAnalyzer.
 *
 * <p>Uses a mocked Neo4j Driver so no running Neo4j instance is needed.
 * Verifies that:
 * <ul>
 *   <li>tagClassesWithService issues the SET Cypher (does not throw).</li>
 *   <li>findSharedClasses correctly maps a query result to SharedClassResult records.</li>
 *   <li>clearServiceTags issues the REMOVE Cypher (does not throw).</li>
 * </ul>
 */
class SharedLibraryAnalyzerTest {

    private Driver        mockDriver;
    private Session       mockSession;
    private Result        mockResult;
    private SharedLibraryAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        mockDriver  = mock(Driver.class);
        mockSession = mock(Session.class);
        mockResult  = mock(Result.class);
        when(mockDriver.session()).thenReturn(mockSession);
        when(mockSession.run(anyString(), anyMap())).thenReturn(mockResult);

        analyzer = new SharedLibraryAnalyzer(mockDriver);
    }

    // ─── tagClassesWithService ────────────────────────────────────────────────

    @Test
    void tagClassesWithService_issuesBatchSetQuery() {
        ServiceBoundary ownerSvc = serviceBoundary("owner-service",
                List.of("org.pet.Owner", "org.pet.OwnerService"));
        ServiceBoundary vetSvc = serviceBoundary("vet-service",
                List.of("org.pet.Vet", "org.pet.VetService"));

        analyzer.tagClassesWithService(List.of(ownerSvc, vetSvc));

        // Verify session.run was called with the SET query
        verify(mockSession, atLeastOnce()).run(
                contains("SET c.serviceBoundary"),
                anyMap());
    }

    @Test
    void tagClassesWithService_skipsEmptyBoundaries() {
        ServiceBoundary empty = serviceBoundary("empty-service", List.of());
        // Should not throw and should not call session.run
        analyzer.tagClassesWithService(List.of(empty));
        verify(mockSession, never()).run(anyString(), anyMap());
    }

    // ─── findSharedClasses ────────────────────────────────────────────────────

    @Test
    void findSharedClasses_returnsMappedResults() {
        // Arrange: mock one result row
        Record row = mockRecord(
                "org.pet.BaseEntity", "BaseEntity", 3,
                List.of("owner-service", "vet-service", "visit-service"));
        when(mockResult.hasNext()).thenReturn(true, false);  // one row then stop
        when(mockResult.next()).thenReturn(row);

        // Act
        List<SharedClassResult> results = analyzer.findSharedClasses(2);

        // Assert
        assertThat(results).hasSize(1);
        SharedClassResult r = results.get(0);
        assertThat(r.fqn()).isEqualTo("org.pet.BaseEntity");
        assertThat(r.shortName()).isEqualTo("BaseEntity");
        assertThat(r.serviceCount()).isEqualTo(3);
        assertThat(r.referencingServices()).containsExactlyInAnyOrder(
                "owner-service", "vet-service", "visit-service");
    }

    @Test
    void findSharedClasses_returnsEmptyListWhenNoResults() {
        when(mockResult.hasNext()).thenReturn(false);
        List<SharedClassResult> results = analyzer.findSharedClasses(2);
        assertThat(results).isEmpty();
    }

    @Test
    void findSharedClasses_skipsBlankFqn() {
        Record row = mockRecord("", "SomeClass", 2, List.of("svc-a", "svc-b"));
        when(mockResult.hasNext()).thenReturn(true, false);
        when(mockResult.next()).thenReturn(row);

        List<SharedClassResult> results = analyzer.findSharedClasses(2);
        assertThat(results).isEmpty();
    }

    // ─── clearServiceTags ─────────────────────────────────────────────────────

    @Test
    void clearServiceTags_issuesRemoveQuery() {
        ServiceBoundary b = serviceBoundary("owner-service", List.of("org.pet.Owner"));
        analyzer.clearServiceTags(List.of(b));
        verify(mockSession, atLeastOnce()).run(contains("REMOVE c.serviceBoundary"), anyMap());
    }

    @Test
    void clearServiceTags_skipsEmptyList() {
        analyzer.clearServiceTags(List.of());
        verify(mockSession, never()).run(anyString(), anyMap());
    }

    // ─── Cross-service detection scenario ────────────────────────────────────

    @Test
    void findSharedClasses_detectsCrossServiceUtilityClass() {
        // Given: BaseEntity is called from 3 different service boundaries
        Record baseEntityRow = mockRecord(
                "org.pet.BaseEntity", "BaseEntity", 3,
                List.of("owner-service", "vet-service", "visit-service"));
        Record dateUtilRow = mockRecord(
                "org.pet.DateUtils", "DateUtils", 2,
                List.of("owner-service", "appointment-service"));

        when(mockResult.hasNext()).thenReturn(true, true, false);
        when(mockResult.next()).thenReturn(baseEntityRow, dateUtilRow);

        List<SharedClassResult> results = analyzer.findSharedClasses(2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).fqn()).isEqualTo("org.pet.BaseEntity");
        assertThat(results.get(0).serviceCount()).isEqualTo(3);
        assertThat(results.get(1).fqn()).isEqualTo("org.pet.DateUtils");
        assertThat(results.get(1).serviceCount()).isEqualTo(2);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static ServiceBoundary serviceBoundary(String name, List<String> fqns) {
        ServiceBoundary b = new ServiceBoundary();
        b.setServiceName(name);
        b.setClassFqns(fqns);
        return b;
    }

    private static Record mockRecord(String fqn, String shortName, int svcCount,
                                      List<String> services) {
        Record record = mock(Record.class);

        Value fqnVal       = Values.value(fqn);
        Value shortNameVal = Values.value(shortName);
        Value countVal     = Values.value(svcCount);
        Value servicesVal  = Values.value(services);

        when(record.get("fqn")).thenReturn(fqnVal);
        when(record.get("shortName")).thenReturn(shortNameVal);
        when(record.get("serviceCount")).thenReturn(countVal);
        when(record.get("referencingServices")).thenReturn(servicesVal);

        return record;
    }
}
