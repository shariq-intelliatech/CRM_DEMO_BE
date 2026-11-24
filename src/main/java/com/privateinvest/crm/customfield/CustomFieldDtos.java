package com.privateinvest.crm.customfield;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shapes for the field registry: every field of a record type, system or custom, and values on records.
 * Every Person and Entity field is here; values live in each record's custom_fields JSONB document.
 */
public final class CustomFieldDtos {

    private CustomFieldDtos() {
    }

    /** Supported field types. */
    public enum DataType {
        TEXT, LONG_TEXT, NUMBER, CURRENCY, PERCENT, DATE, CHECKBOX, BOOLEAN, PICKLIST, MULTI_PICKLIST, EMAIL, PHONE, URL, USER,
        /** Several phone numbers, each with a label from the field's list and exactly one marked primary. */
        PHONE_LIST,
        /** Several email addresses, each with a label from the field's list and exactly one marked primary. */
        EMAIL_LIST;

        public boolean isPicklist() {
            return this == PICKLIST || this == MULTI_PICKLIST;
        }

        /** A list of labelled items (phones or emails). */
        public boolean isList() {
            return this == PHONE_LIST || this == EMAIL_LIST;
        }

        /** Types whose definition has an option list: dropdowns, and the labels of phone and email lists. */
        public boolean hasOptions() {
            return isPicklist() || isList();
        }

        /** The type of each item's value in a list type. */
        public DataType itemType() {
            return this == PHONE_LIST ? PHONE : this == EMAIL_LIST ? EMAIL : this;
        }

        public boolean isNumeric() {
            return this == NUMBER || this == CURRENCY || this == PERCENT;
        }

        public boolean hasLength() {
            return this == TEXT || this == LONG_TEXT;
        }

        public boolean isText() {
            return this == TEXT || this == LONG_TEXT || this == EMAIL || this == PHONE || this == URL;
        }

        /** Types quick search can match by what a user types; Yes/No fields have nothing to type. */
        public boolean isQuickSearchable() {
            return this != CHECKBOX && this != BOOLEAN && this != USER;
        }
    }

    /** Where a field's value lives. Only CUSTOM fields can be created at runtime. */
    public enum Storage {
        /** A real column on the record table. */
        CORE,
        /** A child table (Person email and phone); the primary item is the value. */
        CHILD,
        /** The record's custom_fields JSONB document, keyed by api name. */
        CUSTOM
    }

    public record CustomFieldDef(
            UUID id,
            String object,
            String apiName,
            String label,
            String help,
            DataType dataType,
            /** Picklist set code for PICKLIST and MULTI_PICKLIST; its options are managed like any picklist. */
            String picklist,
            /** Blocks a save when empty. */
            boolean mandatory,
            /** Shows as missing when empty, never blocks. */
            boolean expected,
            /** Values visible only with the CUSTOM_FIELD_SENSITIVE permission. */
            boolean sensitive,
            /** Typed default applied on create, e.g. "GOLD", 0, true. */
            Object defaultValue,
            boolean showInList,
            Integer maxLength,
            Integer decimalPlaces,
            int sortOrder,
            boolean active,
            long version,
            Storage storage,
            /** Fixed key code uses to refer to a system field; null on fields admins created. */
            String systemKey,
            String placeholder,
            /** HALF or FULL: one or both columns of the two-column form. */
            String width,
            /** Extra rules: minLength, min, max, pattern, patternMessage, format (ABN or ACN, checksum checked). */
            Map<String, Object> validation,
            boolean showOnCreate,
            boolean showOnDetail,
            int listOrder,
            /** Included in quick search. */
            boolean searchable,
            boolean filterable,
            boolean sortable,
            boolean editableAfterCreate,
            /** Rules depend on this field being required, so it can't be made optional or disabled. */
            boolean requiredLocked,
            /** Section heading on forms and detail views (default: "Personal Information"). */
            String section,
            /** When the field was (soft) deleted; null while it is live. A deleted field also reads as inactive. */
            OffsetDateTime deletedAt,
            String deletedBy,
            /**
             * When the field shows and when it is required: {"visibleWhen": [condition...], "requiredWhen": [...]}.
             * A condition is {"field", "operator", "value"}; all conditions of a list must hold. A field starting
             * with "$" is a fact about the record, e.g. "$linkedEntityType" (types of the Entities a Person links to).
             */
            Map<String, Object> displayRules,
            /**
             * A value the CRM works out: {"fn": "WEEKS_SINCE" | "DAYS_SINCE", "field": dateField} is computed on
             * read; {"fn": "CHANGED_BY", "fields": [...]} is set to the user who last changed any of those fields,
             * {"fn": "CHANGED_ON", "fields": [...]} to the date of that change.
             * Users can't type into these fields.
             */
            Map<String, Object> formula,
            /** For a PHONE or EMAIL field: its value must be one of the items of this PHONE_LIST / EMAIL_LIST field. */
            String optionsFrom,
            /** The business arm the field serves: CREDIT, CAPITAL or BOTH. */
            String arm) {

        public boolean computed() {
            return formula != null && !formula.isEmpty();
        }

        /** Computed on read and never stored (WEEKS_SINCE, DAYS_SINCE). */
        public boolean derived() {
            return computed() && !stamp();
        }

        /** Stored, and set by the CRM when a watched field changes: who (CHANGED_BY) or when (CHANGED_ON). */
        public boolean stamp() {
            return computed() && ("CHANGED_BY".equals(formula.get("fn")) || "CHANGED_ON".equals(formula.get("fn")));
        }

        public boolean deleted() {
            return deletedAt != null;
        }

        public boolean system() {
            return systemKey != null;
        }

        /** Only fields admins created can be deleted. */
        public boolean deletable() {
            return systemKey == null && storage == Storage.CUSTOM;
        }

        /** A probe used to check a value against a type's standard rules. */
        static CustomFieldDef probe(String object, String key, String label, DataType type, String picklist,
                                    Integer maxLength, Integer decimals, Map<String, Object> validation) {
            return new CustomFieldDef(null, object, key, label, null, type, picklist, false, false, false, null,
                    false, maxLength, decimals, 0, true, 0, Storage.CUSTOM, null, null, "HALF",
                    validation == null ? Map.of() : validation, true, true, 0, false, true, true, true, false,
                    "Personal Information", null, null, Map.of(), null, null, "BOTH");
        }
    }

    /** Layout and behaviour settings shared by create and update. Null means "default" on create, "keep" on update. */
    public record FieldSettings(
            String placeholder,
            String width,
            Map<String, Object> validation,
            Boolean showOnCreate,
            Boolean showOnDetail,
            Boolean searchable,
            Boolean filterable,
            Boolean sortable,
            Boolean editableAfterCreate,
            String section,
            Map<String, Object> displayRules,
            String optionsFrom,
            String arm) {
    }

    public record CustomFieldCreate(
            @NotBlank(message = "Choose Person or Entity.") String object,
            @NotBlank(message = "Enter a field label.") String label,
            @NotNull(message = "Choose a field type.") DataType dataType,
            String help,
            boolean mandatory,
            boolean expected,
            boolean sensitive,
            /** For dropdowns, the default may be given as an option label or code. */
            Object defaultValue,
            boolean showInList,
            Integer maxLength,
            Integer decimalPlaces,
            /** Starting options for a dropdown field (or the labels of a phone or email list), in order. */
            List<String> picklistValues,
            String placeholder,
            String width,
            Map<String, Object> validation,
            Boolean showOnCreate,
            Boolean showOnDetail,
            Boolean searchable,
            Boolean filterable,
            Boolean sortable,
            Boolean editableAfterCreate,
            String section,
            Map<String, Object> displayRules,
            String optionsFrom,
            String arm) {

        FieldSettings settings() {
            return new FieldSettings(placeholder, width, validation, showOnCreate, showOnDetail, searchable, filterable,
                    sortable, editableAfterCreate, section, displayRules, optionsFrom, arm);
        }
    }

    /** The type, storage and api name never change after creation, so existing values always stay valid. */
    public record CustomFieldUpdate(
            @NotBlank(message = "Enter a field label.") String label,
            String help,
            boolean mandatory,
            boolean expected,
            boolean sensitive,
            Object defaultValue,
            boolean showInList,
            Integer maxLength,
            Integer decimalPlaces,
            /** The version the editor loaded; a mismatch means someone else changed the field. */
            long version,
            String placeholder,
            String width,
            Map<String, Object> validation,
            Boolean showOnCreate,
            Boolean showOnDetail,
            Boolean searchable,
            Boolean filterable,
            Boolean sortable,
            Boolean editableAfterCreate,
            String section,
            Map<String, Object> displayRules,
            String optionsFrom,
            String arm) {

        FieldSettings settings() {
            return new FieldSettings(placeholder, width, validation, showOnCreate, showOnDetail, searchable, filterable,
                    sortable, editableAfterCreate, section, displayRules, optionsFrom, arm);
        }
    }

    public record ActiveUpdate(boolean active) {
    }

    public record MoveRequest(int direction) {
    }

    /** The full order of a record type's fields: form order, or list column order when {@code list} is true. */
    public record OrderRequest(@NotBlank String object, @NotNull List<UUID> fieldIds, boolean list) {
    }

    /** A deleted field as Setup → Deleted fields lists it, with the values it still keeps. */
    public record DeletedField(
            @JsonUnwrapped CustomFieldDef field,
            String deletedByName,
            long recordsWithValue,
            long archivedRecordsWithValue) {
    }

    /** How many records hold a value, shown before a delete. */
    public record FieldUsage(UUID fieldId, String label, boolean deletable, long recordsWithValue, long archivedRecordsWithValue) {
    }

    /** A field as forms, lists and filters see it: the definition plus what this user may do with it. */
    public record FieldView(
            @JsonUnwrapped CustomFieldDef field,
            boolean system,
            boolean deletable,
            boolean canEdit,
            List<String> operators) {
    }

    /** Values to set on one record. A null value clears that field. Fields not listed are left as they are. */
    public record ValuesUpdate(@NotNull Map<String, Object> values) {
    }
}
