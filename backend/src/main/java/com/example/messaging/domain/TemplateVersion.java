package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "template_version")
@Getter
@Setter
@NoArgsConstructor
public class TemplateVersion {

    @Id
    @Column(length = 36)
    private String id;
    private String templateId;
    @Column(name = "version_no")
    private int versionNo;
    private String locale;
    @Enumerated(EnumType.STRING)
    private TemplateStatus status;
    private String subject;
    @Column(length = 10000)
    private String body;
    /** JSON array of declared variable names (the allow-list for the renderer). */
    @Column(name = "variables_json", length = 2000)
    private String variablesJson;
    private String createdBy;
    private String approvedBy;
    private Instant publishedAt;
    private String rejectionComment;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;
}
