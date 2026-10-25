package com.example.messaging.repository;

import com.example.messaging.domain.BulkJobFailure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobFailureRepository extends JpaRepository<BulkJobFailure, String> {

    Page<BulkJobFailure> findByJobId(String jobId, Pageable pageable);

    long countByJobId(String jobId);
}
