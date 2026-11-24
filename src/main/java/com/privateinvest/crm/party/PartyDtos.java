package com.privateinvest.crm.party;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Shapes for Entity links. Persons and Entities themselves are configurable records
 * ({@link com.privateinvest.crm.record.RecordDtos}). Picklist fields carry value codes.
 */
public final class PartyDtos {

    private PartyDtos() {
    }

    /** One of a Person's Entity links, as its Linked Entities block shows it. */
    public record PersonLink(
            UUID id,
            UUID entityId,
            String entityName,
            String entityType,
            String role,
            String jobTitle,
            String department,
            LocalDate dateLinked,
            String email,
            boolean primary) {
    }

    /** One of an Entity's linked Persons, as its Linked Persons block shows it. */
    public record EntityPerson(
            UUID linkId,
            UUID personId,
            String personName,
            String primaryEmail,
            String primaryPhone,
            String role,
            String jobTitle,
            String department,
            LocalDate dateLinked,
            boolean primary) {
    }

    public record LinkCreate(
            @NotNull UUID personId,
            @NotNull UUID entityId,
            @NotBlank(message = "Pick a role.") String role,
            String jobTitle,
            String department,
            LocalDate dateLinked,
            /** One of the Person's emails, or a new one that is then added to the Person (brief §3.2). */
            String email,
            boolean primary) {
    }

    public record LinkUpdate(@NotBlank(message = "Pick a role.") String role, String jobTitle, String department, String email) {
    }
}
