package com.example.messaging.repository;

import com.example.messaging.domain.ReplayGuardEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayGuardRepository extends JpaRepository<ReplayGuardEntry, String> {}
