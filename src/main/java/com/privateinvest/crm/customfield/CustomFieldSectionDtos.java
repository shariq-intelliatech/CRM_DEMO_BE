package com.privateinvest.crm.customfield;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Shapes for persisted custom-field sections (headings that group fields and control section order). */
public final class CustomFieldSectionDtos {

    private CustomFieldSectionDtos() {
    }

    public record SectionDef(
            UUID id,
            String object,
            String name,
            String description,
            int sortOrder,
            long version,
            long fieldCount,
            long activeFieldCount,
            long deletedFieldCount) {
    }

    public record SectionCreate(
            @NotBlank(message = "Choose Person or Entity.") String object,
            @NotBlank(message = "Enter a section name.") @Size(max = 100, message = "Section name must be at most 100 characters.") String name,
            @Size(max = 300, message = "Description must be at most 300 characters.") String description) {
    }

    public record SectionUpdate(
            @NotBlank(message = "Enter a section name.") @Size(max = 100, message = "Section name must be at most 100 characters.") String name,
            @Size(max = 300, message = "Description must be at most 300 characters.") String description,
            long version) {
    }

    public record SectionMoveRequest(int direction) {
    }

    public record SectionOrderRequest(@NotBlank String object, @NotNull List<UUID> sectionIds) {
    }
}
