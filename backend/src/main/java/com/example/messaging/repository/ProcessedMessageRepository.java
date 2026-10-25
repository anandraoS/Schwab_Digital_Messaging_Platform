package com.example.messaging.repository;

import com.example.messaging.domain.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    boolean existsByDispatchId(String dispatchId);
}
