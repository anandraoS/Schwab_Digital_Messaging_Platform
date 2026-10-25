package com.example.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
public class Tenant extends PersistableEntity {

    @Id
    @Column(length = 36)
    private String id;
    private String name;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
