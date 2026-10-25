package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "template")
@Getter
@Setter
@NoArgsConstructor
public class Template extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String tenantId;
    private String code;
    private String name;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Category category;
    private Instant createdAt;
    private Instant updatedAt;
}
