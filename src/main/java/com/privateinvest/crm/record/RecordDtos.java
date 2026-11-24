package com.privateinvest.crm.record;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordField;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldCreate;

/** Request and response shapes shared by every configurable record type (Person, Entity). */
public final class RecordDtos {

    private RecordDtos() {
    }

    /** Flags a record carries outside its fields: Hot (brief §3.8.2) and the possible-duplicate reason (§3.8.3). */
    public record RecordFlags(OffsetDateTime hotSince, String possibleDuplicateReason) {
    }

    /**
     * One record: every enabled field's value by api name, its one-off fields and system information. {@code title}
     * is built from the fields the "record name" rule names. {@code context} holds facts that display conditions
     * can use besides fields, e.g. {@code linkedEntityType}. Person adds {@code links} (its Entities) and the
     * inherited Entity type; Entity adds {@code persons} (its linked Persons).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordDetail(
            UUID id,
            String object,
            String title,
            Map<String, Object> values,
            List<RecordField> recordFields,
            /** Expected fields that are still empty (hidden fields and fields removed from this record left out). */
            List<String> dataGaps,
            /** Fields removed from this record only; they don't show on its record or edit form. */
            List<String> hiddenFields,
            RecordFlags flags,
            Map<String, Object> context,
            boolean archived,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            /** Send back in If-Match when saving, so a stale screen can't overwrite a newer save. */
            long version,
            Object links,
            Object persons,
            /** Person only: read through the primary Entity link, never stored on the Person (brief §3.4). */
            String inheritedEntityType) {
    }

    /** What a record type adds to its detail: linked records and the facts its conditions use. */
    public record Related(Object links, Object persons, String inheritedEntityType, Map<String, Object> context) {
        public static final Related NONE = new Related(null, null, null, Map.of());
    }

    /**
     * A new record: field values by api name. {@code customFields} is an older name for the same map; both are
     * merged, {@code values} winning. Which fields are required, and how each is checked, comes from the registry.
     */
    public record RecordCreate(
            Map<String, Object> values,
            Map<String, Object> customFields,
            /** Set to save past a duplicate warning (brief §3.8.3). Never passes a unique-field (G1) block. */
            String duplicateReason,
            /** One-off fields for this record only, created with it. */
            List<@Valid RecordFieldCreate> recordFields,
            /** Field values sent at the top level, as the original fixed Person body did ({"firstName": ...}). */
            @JsonAnySetter Map<String, Object> topLevel) {
    }

    /** Field values to change, by api name. Only the fields sent change; null clears one. */
    public record RecordPatch(@NotNull(message = "Send the values to change.") Map<String, Object> values) {
    }

    public record ArchiveRequest(String reason) {
    }

    public record RestoreRequest(String duplicateReason) {
    }

    public record HotUpdate(boolean hot) {
    }

    /** Hooks a record type adds to the shared engine. */
    public interface RecordExtension {
        String object();

        /** Linked records and condition facts for one record. */
        Related related(UUID id);
    }
}
