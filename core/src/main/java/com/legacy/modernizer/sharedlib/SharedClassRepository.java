package com.legacy.modernizer.sharedlib;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedClassRepository extends JpaRepository<SharedClass, Long> {
    List<SharedClass> findByJobId(Long jobId);
    void deleteByJobId(Long jobId);
    int countByJobId(Long jobId);
}
