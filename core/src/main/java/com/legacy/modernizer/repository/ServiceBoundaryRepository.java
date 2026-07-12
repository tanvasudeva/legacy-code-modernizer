package com.legacy.modernizer.repository;

import com.legacy.modernizer.model.ServiceBoundary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ServiceBoundaryRepository extends JpaRepository<ServiceBoundary, Long> {
    List<ServiceBoundary> findByJobId(Long jobId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ServiceBoundary b WHERE b.jobId = :jobId")
    void deleteByJobId(@Param("jobId") Long jobId);
}
