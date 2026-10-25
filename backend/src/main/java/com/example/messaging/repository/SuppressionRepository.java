package com.example.messaging.repository;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Suppression;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuppressionRepository extends JpaRepository<Suppression, String> {

    boolean existsByTenantIdAndChannelAndAddressHash(String tenantId, Channel channel, String addressHash);

    Optional<Suppression> findByTenantIdAndChannelAndAddressHash(String tenantId, Channel channel, String addressHash);
}
