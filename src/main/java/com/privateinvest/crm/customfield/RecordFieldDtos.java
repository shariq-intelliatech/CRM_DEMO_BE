package com.privateinvest.crm.customfield;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;

/** One-off fields: a labelled value that belongs to a single Person or Entity only. */
public final class RecordFieldDtos {

    private RecordFieldDtos() {
    }

    public record RecordField(UUID id, String label, DataType dataType, Object value, int sortOrder, long version) {
    }

    public record RecordFieldCreate(
            @NotBlank(message = "Enter a label for the field.") String label,
            @NotNull(message = "Choose a field type.") DataType dataType,
            Object value) {
    }

    /** The type can't change; the label and value can. {@code version} guards against overwriting someone else's edit. */
    public record RecordFieldUpdate(
            @NotBlank(message = "Enter a label for the field.") String label,
            Object value,
            long version) {
    }
}
