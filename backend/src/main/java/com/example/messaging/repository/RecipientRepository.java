package com.example.messaging.repository;

import com.example.messaging.domain.Recipient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RecipientRepository extends JpaRepository<Recipient, String>, JpaSpecificationExecutor<Recipient> {

    Optional<Recipient> findByTenantIdAndExternalRef(String tenantId, String externalRef);
}
