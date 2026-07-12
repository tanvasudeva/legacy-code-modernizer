package com.legacy.modernizer.sharedlib;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SharedClassRepository extends JpaRepository<SharedClass, Long> {
    List<SharedClass> findByJobId(Long jobId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SharedClass s WHERE s.jobId = :jobId")
    void deleteByJobId(@Param("jobId") Long jobId);

    int countByJobId(Long jobId);
}
