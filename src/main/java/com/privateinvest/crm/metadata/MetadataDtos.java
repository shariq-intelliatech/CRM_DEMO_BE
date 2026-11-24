package com.privateinvest.crm.metadata;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;

/** The configuration the frontend renders from: labels, picklists and settings (Salesforce-style metadata). */
public final class MetadataDtos {

    private MetadataDtos() {
    }

    public record Metadata(
            Map<String, ObjectDef> objects,
            List<FieldDef> fields,
            Map<String, PicklistSet> picklists,
            Map<String, Object> settings,
            /** Runtime custom fields on Person and Entity, active and retired. */
            List<CustomFieldDef> customFields) {

        public Metadata withCustomFields(List<CustomFieldDef> custom) {
            return new Metadata(objects, fields, picklists, settings, custom);
        }
    }

    public record ObjectDef(String apiName, String label, String labelPlural) {
    }

    public record FieldDef(String object, String apiName, String label, String help) {
    }

    public record PicklistSet(
            String code,
            String name,
            String description,
            boolean multi,
            boolean locked,
            boolean placeholder,
            String parentSet,
            List<PicklistValue> values) {
    }

    public record PicklistValue(String code, String label, String arm, boolean active, boolean system, String parent) {
    }

    public record User(String id, String name, String email, String initials) {
    }

    /** The signed-in user with what they may do; the UI hides actions they can't take. */
    public record Me(String id, String name, String email, String initials, List<String> roles, List<String> permissions) {
    }

    public record ObjectLabelUpdate(@NotBlank String label, @NotBlank String labelPlural) {
    }

    public record FieldLabelUpdate(@NotBlank String label) {
    }

    /** Partial update of one picklist value: any field left null stays as it is. */
    public record PicklistValueUpdate(String label, String arm, Boolean active, Integer sortOrder) {
    }

    public record PicklistOrder(@jakarta.validation.constraints.NotEmpty List<String> codes) {
    }

    public record PicklistValueCreate(@NotBlank String label, String parent) {
    }
}
