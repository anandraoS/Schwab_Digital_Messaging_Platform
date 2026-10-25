package com.example.messaging.repository;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Template;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TemplateRepository extends JpaRepository<Template, String>, JpaSpecificationExecutor<Template> {

    Optional<Template> findByTenantIdAndCodeAndChannel(String tenantId, String code, Channel channel);
}
