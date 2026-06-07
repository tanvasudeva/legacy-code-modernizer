package com.legacy.modernizer.bundle;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.repository.ArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BundleAssemblerTest {

    @Mock ArtifactRepository artifactRepository;

    BundleAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new BundleAssembler(artifactRepository);
    }

    // -------------------------------------------------------------------------
    // isServiceSlug
    // -------------------------------------------------------------------------

    @Test
    void serviceSlugWithHyphenIsAccepted() {
        assertThat(BundleAssembler.isServiceSlug("owner-service")).isTrue();
    }

    @Test
    void fullyQualifiedNameIsRejected() {
        assertThat(BundleAssembler.isServiceSlug("org.petclinic.Owner")).isFalse();
    }

    @Test
    void nullAndBlankAreRejected() {
        assertThat(BundleAssembler.isServiceSlug(null)).isFalse();
        assertThat(BundleAssembler.isServiceSlug("")).isFalse();
        assertThat(BundleAssembler.isServiceSlug("  ")).isFalse();
    }

    // -------------------------------------------------------------------------
    // generateRootPom
    // -------------------------------------------------------------------------

    @Test
    void rootPomContainsAllModules() {
        String pom = assembler.generateRootPom(List.of("owner-service", "vet-service"), 42L);
        assertThat(pom).contains("<module>owner-service</module>");
        assertThat(pom).contains("<module>vet-service</module>");
    }

    @Test
    void rootPomHasMultiModulePackaging() {
        String pom = assembler.generateRootPom(List.of("owner-service"), 1L);
        assertThat(pom).contains("<packaging>pom</packaging>");
        assertThat(pom).contains("modernized-monolith");
    }

    @Test
    void rootPomHasJava21Properties() {
        String pom = assembler.generateRootPom(List.of(), 1L);
        assertThat(pom).contains("<java.version>21</java.version>");
        assertThat(pom).contains("3.2.5");
    }

    @Test
    void rootPomWithNoServicesHasNoModulesBlock() {
        String pom = assembler.generateRootPom(List.of(), 1L);
        assertThat(pom).doesNotContain("<modules>");
    }

    // -------------------------------------------------------------------------
    // generateDockerCompose
    // -------------------------------------------------------------------------

    @Test
    void dockerComposeContainsAllServices() {
        String dc = assembler.generateDockerCompose(List.of("owner-service", "vet-service"));
        assertThat(dc).contains("  owner-service:");
        assertThat(dc).contains("  vet-service:");
    }

    @Test
    void dockerComposeHasDbService() {
        String dc = assembler.generateDockerCompose(List.of("owner-service"));
        assertThat(dc).contains("  db:");
        assertThat(dc).contains("postgres:16-alpine");
    }

    @Test
    void dockerComposePortsIncrementPerService() {
        String dc = assembler.generateDockerCompose(List.of("owner-service", "vet-service"));
        assertThat(dc).contains("\"8081:8080\"");
        assertThat(dc).contains("\"8082:8080\"");
    }

    @Test
    void dockerComposeServicesDependOnDb() {
        String dc = assembler.generateDockerCompose(List.of("owner-service"));
        assertThat(dc).contains("depends_on:");
        assertThat(dc).contains("      - db");
    }

    // -------------------------------------------------------------------------
    // assemble — ZIP structure
    // -------------------------------------------------------------------------

    @Test
    void zipAlwaysContainsRootPomAndDockerCompose() throws Exception {
        when(artifactRepository.findByJobId(1L)).thenReturn(List.of());

        Map<String, String> entries = unzip(assemble(1L));
        assertThat(entries).containsKey("pom.xml");
        assertThat(entries).containsKey("docker-compose.yml");
    }

    @Test
    void serviceCodeRoutedUnderServiceDir() throws Exception {
        Artifact a = artifact("owner-service", ArtifactType.SERVICE_CODE,
                "src/main/java/com/modernized/ownerservice/entity/Owner.java", "class Owner{}");
        when(artifactRepository.findByJobId(1L)).thenReturn(List.of(a));

        Map<String, String> entries = unzip(assemble(1L));
        assertThat(entries).containsKey(
                "owner-service/src/main/java/com/modernized/ownerservice/entity/Owner.java");
    }

    @Test
    void docArtifactsRoutedUnderServiceDocsDir() throws Exception {
        Artifact a = artifact("owner-service", ArtifactType.OPENAPI_SPEC,
                "docs/openapi.yaml", "openapi: 3.0.3");
        when(artifactRepository.findByJobId(1L)).thenReturn(List.of(a));

        Map<String, String> entries = unzip(assemble(1L));
        assertThat(entries).containsKey("owner-service/docs/openapi.yaml");
        assertThat(entries.get("owner-service/docs/openapi.yaml")).contains("openapi: 3.0.3");
    }

    @Test
    void adrAndRunbookAlsoBundled() throws Exception {
        List<Artifact> arts = List.of(
                artifact("vet-service", ArtifactType.ADR,     "docs/adr.md",     "# ADR"),
                artifact("vet-service", ArtifactType.RUNBOOK, "docs/runbook.md", "# Runbook")
        );
        when(artifactRepository.findByJobId(1L)).thenReturn(arts);

        Map<String, String> entries = unzip(assemble(1L));
        assertThat(entries).containsKey("vet-service/docs/adr.md");
        assertThat(entries).containsKey("vet-service/docs/runbook.md");
    }

    @Test
    void originalArtifactsAreExcluded() throws Exception {
        Artifact orig = artifact("org.petclinic.Owner", ArtifactType.ORIGINAL,
                "Owner.java", "class Owner{}");
        when(artifactRepository.findByJobId(1L)).thenReturn(List.of(orig));

        Map<String, String> entries = unzip(assemble(1L));
        // Only root files; no service dir for fully-qualified classFqn
        assertThat(entries.keySet())
                .doesNotContain("org.petclinic.Owner/Owner.java");
    }

    @Test
    void manifestFileCountMatchesZipEntries() throws Exception {
        Artifact a = artifact("owner-service", ArtifactType.SERVICE_CODE, "pom.xml", "<project/>");
        when(artifactRepository.findByJobId(1L)).thenReturn(List.of(a));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BundleManifest manifest = assembler.assemble(1L, buf);
        Map<String, String> entries = unzip(buf.toByteArray());

        assertThat(manifest.fileCount()).isEqualTo(entries.size());
    }

    @Test
    void rootPomModuleListMatchesServiceNamesInManifest() throws Exception {
        List<Artifact> arts = List.of(
                artifact("owner-service", ArtifactType.SERVICE_CODE, "pom.xml", "<project/>"),
                artifact("vet-service",   ArtifactType.SERVICE_CODE, "pom.xml", "<project/>")
        );
        when(artifactRepository.findByJobId(1L)).thenReturn(arts);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BundleManifest manifest = assembler.assemble(1L, buf);

        assertThat(manifest.serviceNames()).containsExactlyInAnyOrder("owner-service", "vet-service");

        Map<String, String> entries = unzip(buf.toByteArray());
        String rootPom = entries.get("pom.xml");
        for (String svc : manifest.serviceNames()) {
            assertThat(rootPom).contains("<module>" + svc + "</module>");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private byte[] assemble(Long jobId) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assembler.assemble(jobId, buf);
        return buf.toByteArray();
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes()));
                zis.closeEntry();
            }
        }
        return entries;
    }

    private static Artifact artifact(String classFqn, ArtifactType type,
                                     String filePath, String content) {
        return Artifact.builder()
                .id(1L).jobId(1L).classFqn(classFqn)
                .artifactType(type).filePath(filePath).content(content)
                .build();
    }
}
