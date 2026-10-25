package com.example.messaging.repository;

import com.example.messaging.domain.BulkJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BulkJobRepository extends JpaRepository<BulkJob, String>, JpaSpecificationExecutor<BulkJob> {}
