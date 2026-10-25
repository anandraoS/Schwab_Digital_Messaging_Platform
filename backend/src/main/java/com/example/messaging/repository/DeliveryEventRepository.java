package com.example.messaging.repository;

import com.example.messaging.domain.DeliveryEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryEventRepository
        extends JpaRepository<DeliveryEvent, String>, JpaSpecificationExecutor<DeliveryEvent> {

    List<DeliveryEvent> findByMessageIdOrderByOccurredAtAsc(String messageId);

    Optional<DeliveryEvent> findByProviderIdAndProviderEventId(String providerId, String providerEventId);

    boolean existsByProviderIdAndProviderEventId(String providerId, String providerEventId);
}
