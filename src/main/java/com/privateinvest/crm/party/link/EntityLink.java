package com.privateinvest.crm.party.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Person ↔ Entity with the PrivateInvest role (brief §3.5.2). One primary link per Person (§3.4). */
@Entity
@Table(name = "entity_link")
@Getter
@Setter
@NoArgsConstructor
public class EntityLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID personId;
    private UUID entityId;
    private String role;
    private String jobTitle;
    private String department;
    private LocalDate dateLinked;
    private String email;

    @Column(name = "is_primary")
    private boolean primary;

    private OffsetDateTime createdAt;
    private String createdBy;
}
