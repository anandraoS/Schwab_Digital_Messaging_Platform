package com.example.messaging.repository;

import com.example.messaging.domain.TemplateStatus;
import com.example.messaging.domain.TemplateVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateVersionRepository extends JpaRepository<TemplateVersion, String> {

    List<TemplateVersion> findByTemplateIdOrderByVersionNoAscLocaleAsc(String templateId);

    /** One query for a whole page of templates (avoids N+1 when listing templates with versions). */
    List<TemplateVersion> findByTemplateIdIn(Collection<String> templateIds);

    Optional<TemplateVersion> findByTemplateIdAndVersionNoAndLocale(String templateId, int versionNo, String locale);

    List<TemplateVersion> findByTemplateIdAndLocaleAndStatus(String templateId, String locale, TemplateStatus status);

    @Query("select coalesce(max(v.versionNo), 0) from TemplateVersion v where v.templateId = :templateId")
    int maxVersionNo(@Param("templateId") String templateId);

    /** Highest PUBLISHED version for the locale (ADR-0007: unpinned requests pin this at accept time). */
    Optional<TemplateVersion> findFirstByTemplateIdAndLocaleAndStatusOrderByVersionNoDesc(
            String templateId, String locale, TemplateStatus status);

    Optional<TemplateVersion> findByTemplateIdAndVersionNoAndLocaleAndStatus(
            String templateId, int versionNo, String locale, TemplateStatus status);
}
