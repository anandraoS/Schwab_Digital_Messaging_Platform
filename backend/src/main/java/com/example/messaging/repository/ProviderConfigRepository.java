package com.example.messaging.repository;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.ProviderConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, String> {

    Optional<ProviderConfig> findByProviderId(String providerId);

    List<ProviderConfig> findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel channel);
}
