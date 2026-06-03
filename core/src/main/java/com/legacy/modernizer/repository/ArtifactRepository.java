package com.legacy.modernizer.repository;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    List<Artifact> findByJobId(Long jobId);

    List<Artifact> findByJobIdAndClassFqn(Long jobId, String classFqn);

    List<Artifact> findByJobIdAndArtifactType(Long jobId, ArtifactType artifactType);
}
