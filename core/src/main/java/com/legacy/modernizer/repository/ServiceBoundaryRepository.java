package com.legacy.modernizer.repository;

import com.legacy.modernizer.model.ServiceBoundary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceBoundaryRepository extends JpaRepository<ServiceBoundary, Long> {
    List<ServiceBoundary> findByJobId(Long jobId);
    void deleteByJobId(Long jobId);
}
