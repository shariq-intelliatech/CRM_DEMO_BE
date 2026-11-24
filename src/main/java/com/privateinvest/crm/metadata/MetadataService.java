package com.privateinvest.crm.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.metadata.MetadataDtos.FieldDef;
import com.privateinvest.crm.metadata.MetadataDtos.Metadata;
import com.privateinvest.crm.metadata.MetadataDtos.ObjectDef;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistSet;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValue;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValueCreate;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValueUpdate;
import com.privateinvest.crm.metadata.MetadataDtos.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and changes configuration (brief §2.2: admins change lists and settings without a release),
 * and checks that picklist codes written to records are real values.
 */
@Service
@RequiredArgsConstructor
public class MetadataService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public Metadata load() {
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        jdbc.sql("SELECT api_name, label, label_plural FROM object_def ORDER BY api_name")
                .query((rs, i) -> new ObjectDef(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list()
                .forEach(o -> objects.put(o.apiName(), o));

        List<FieldDef> fields = jdbc.sql("""
                        SELECT object_api_name, api_name, label, help_text FROM (
                            SELECT object_api_name, api_name, label, help_text, sort_order FROM field_def
                            UNION ALL
                            -- Person fields all live in the field registry.
                            SELECT object_api_name, api_name, label, help_text, sort_order
                            FROM custom_field_def WHERE object_api_name = 'person'
                        ) f
                        ORDER BY object_api_name, sort_order""")
                .query((rs, i) -> new FieldDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();

        Map<String, List<PicklistValue>> valuesBySet = jdbc.sql("""
                        SELECT set_code, code, label, arm, active, system_value, parent_code
                        FROM picklist_value ORDER BY set_code, sort_order""")
                .query((rs, i) -> Map.entry(rs.getString(1), new PicklistValue(
                        rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getString(7))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        Map<String, PicklistSet> picklists = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT code, name, description, multi_select, locked, placeholder, parent_set_code
                        FROM picklist_set ORDER BY sort_order""")
                .query((rs, i) -> new PicklistSet(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7),
                        valuesBySet.getOrDefault(rs.getString(1), List.of())))
                .list()
                .forEach(s -> picklists.put(s.code(), s));

        return new Metadata(objects, fields, picklists, settings(), List.of());
    }

    public Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        jdbc.sql("SELECT setting_key, setting_value::text FROM app_setting ORDER BY setting_key")
                .query((rs, i) -> Map.entry(rs.getString(1), json.readValue(rs.getString(2), Object.class)))
                .list()
                .forEach(e -> settings.put(e.getKey(), e.getValue()));
        return settings;
    }

    public int intSetting(String key, int fallback) {
        return settings().get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public List<User> users() {
        return jdbc.sql("SELECT id, name, email, initials FROM app_user WHERE active ORDER BY name")
                .query((rs, i) -> new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    public boolean userExists(String id) {
        return id != null && jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id AND active")
                .param("id", id).query(Long.class).single() > 0;
    }

    /* ---------- Checks used by record services ---------- */

    /**
     * Adds an error when {@code value} is not a code in the picklist, or is a retired value that the
     * record did not already hold (retired values stay on old records, brief §3.3.3).
     */
    public void checkCode(Map<String, String> errors, String field, String setCode, String value, String previous) {
        if (value == null || value.isBlank()) {
            return;
        }
        List<Boolean> active = jdbc.sql("SELECT active FROM picklist_value WHERE set_code = :set AND code = :code")
                .param("set", setCode).param("code", value)
                .query(Boolean.class).list();
        if (active.isEmpty()) {
            errors.put(field, "'" + value + "' is not a value in the " + setCode + " list.");
        } else if (!active.getFirst() && !Objects.equals(value, previous)) {
            errors.put(field, "'" + value + "' has been retired and can't be chosen for new data.");
        }
    }

    public void checkCodes(Map<String, String> errors, String field, String setCode,
                           Collection<String> values, Collection<String> previous) {
        for (String v : values) {
            checkCode(errors, field, setCode, v, previous != null && previous.contains(v) ? v : null);
        }
    }

    /* ---------- Setup changes ---------- */

    @Transactional
    public void updateObjectLabel(String apiName, String label, String labelPlural) {
        int n = jdbc.sql("UPDATE object_def SET label = :label, label_plural = :plural WHERE api_name = :api")
                .param("label", label.trim()).param("plural", labelPlural.trim()).param("api", apiName).update();
        if (n == 0) {
            throw new NotFoundException("Object", apiName);
        }
        audit.record("object_def", null, "update", null, Map.of("apiName", apiName, "label", label, "labelPlural", labelPlural), null);
    }

    @Transactional
    public void updateFieldLabel(String object, String apiName, String label) {
        int n = jdbc.sql("UPDATE field_def SET label = :label WHERE object_api_name = :obj AND api_name = :api")
                .param("label", label.trim()).param("obj", object).param("api", apiName).update();
        if (n == 0) {
            throw new NotFoundException("Field", object + "." + apiName);
        }
        audit.record("field_def", null, "update", null, Map.of("object", object, "apiName", apiName, "label", label), null);
    }

    @Transactional
    public void updateValue(String setCode, String code, PicklistValueUpdate u) {
        Map<String, Object> current = jdbc.sql("""
                        SELECT v.active, v.system_value, s.locked FROM picklist_value v
                        JOIN picklist_set s ON s.code = v.set_code
                        WHERE v.set_code = :set AND v.code = :code""")
                .param("set", setCode).param("code", code)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Picklist value", setCode + "." + code));
        boolean protectedValue = (Boolean) current.get("system_value") || (Boolean) current.get("locked");
        if (protectedValue && Boolean.FALSE.equals(u.active())) {
            throw new FieldValidationException(Map.of("active", "This value is used by the CRM's rules, so it can't be retired."));
        }
        if ((Boolean) current.get("locked") && u.arm() != null) {
            throw new FieldValidationException(Map.of("arm", "This list is locked. Its values can be relabelled and reordered only."));
        }
        if (u.arm() != null && !List.of("CREDIT", "CAPITAL", "BOTH").contains(u.arm())) {
            throw new FieldValidationException(Map.of("arm", "Arm must be CREDIT, CAPITAL or BOTH."));
        }
        jdbc.sql("""
                        UPDATE picklist_value SET
                          label = COALESCE(:label, label),
                          arm = COALESCE(:arm, arm),
                          active = COALESCE(:active, active),
                          sort_order = COALESCE(:sort, sort_order)
                        WHERE set_code = :set AND code = :code""")
                .param("label", u.label() == null ? null : u.label().trim())
                .param("arm", u.arm())
                .param("active", u.active())
                .param("sort", u.sortOrder())
                .param("set", setCode).param("code", code)
                .update();
        audit.record("picklist_value", null, "update", null, Map.of("set", setCode, "code", code, "change", u), null);
    }

    /** Puts a list's values in the given order. Every value of the list must be named once. */
    @Transactional
    public void orderValues(String setCode, List<String> codes) {
        List<String> current = jdbc.sql("SELECT code FROM picklist_value WHERE set_code = :set ORDER BY sort_order")
                .param("set", setCode).query(String.class).list();
        if (current.isEmpty()) {
            throw new NotFoundException("Picklist", setCode);
        }
        if (codes == null || codes.size() != current.size() || !new java.util.HashSet<>(codes).equals(new java.util.HashSet<>(current))) {
            throw new FieldValidationException(Map.of("codes", "List each value of this picklist once."));
        }
        for (int i = 0; i < codes.size(); i++) {
            jdbc.sql("UPDATE picklist_value SET sort_order = :sort WHERE set_code = :set AND code = :code")
                    .param("sort", i).param("set", setCode).param("code", codes.get(i)).update();
        }
        audit.record("picklist_value", null, "order", Map.of("set", setCode, "codes", current),
                Map.of("set", setCode, "codes", codes), null);
    }

    @Transactional
    public PicklistValue addValue(String setCode, PicklistValueCreate c) {
        Map<String, Object> set = jdbc.sql("SELECT locked, parent_set_code FROM picklist_set WHERE code = :set")
                .param("set", setCode).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Picklist", setCode));
        if ((Boolean) set.get("locked")) {
            throw new FieldValidationException(Map.of("label", "This list is locked. Values can't be added."));
        }
        if (set.get("parent_set_code") != null && (c.parent() == null || c.parent().isBlank())) {
            throw new FieldValidationException(Map.of("parent", "Choose the parent value for this dependent list."));
        }
        // A value is added only when the list doesn't already hold it (field map: Entity Role, "as long as it does
        // not exist"). Labels compare trimmed, ignoring case and repeated spaces; a retired match is restored instead.
        // In a dependent list the same label may sit under different parent values.
        Map<String, Object> existing = jdbc.sql("""
                        SELECT label, active FROM picklist_value
                        WHERE set_code = :set AND parent_code IS NOT DISTINCT FROM :parent
                          AND lower(regexp_replace(btrim(label), '\\s+', ' ', 'g')) = :label
                        ORDER BY active DESC LIMIT 1""")
                .param("set", setCode)
                .param("parent", c.parent() == null || c.parent().isBlank() ? null : c.parent())
                .param("label", c.label().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT))
                .query().listOfRows().stream().findFirst().orElse(null);
        if (existing != null) {
            throw new FieldValidationException(Map.of("label", Boolean.TRUE.equals(existing.get("active"))
                    ? "\"" + existing.get("label") + "\" is already in this list."
                    : "\"" + existing.get("label") + "\" is already in this list but retired. Make it active again instead."));
        }
        String base = c.label().trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        String code = base.isEmpty() ? "VALUE" : base;
        for (int i = 2; exists(setCode, code); i++) {
            code = base + "_" + i;
        }
        int nextOrder = jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM picklist_value WHERE set_code = :set")
                .param("set", setCode).query(Integer.class).single();
        jdbc.sql("""
                        INSERT INTO picklist_value (set_code, code, label, arm, active, system_value, parent_code, sort_order)
                        VALUES (:set, :code, :label, 'BOTH', TRUE, FALSE, :parent, :sort)""")
                .param("set", setCode).param("code", code).param("label", c.label().trim())
                .param("parent", c.parent()).param("sort", nextOrder)
                .update();
        audit.record("picklist_value", null, "create", null, Map.of("set", setCode, "code", code, "label", c.label()), null);
        return new PicklistValue(code, c.label().trim(), "BOTH", true, false, c.parent());
    }

    @Transactional
    public void updateSettings(Map<String, Object> changes) {
        if (changes.containsKey("personRules")) {
            // Person rules name fields and are checked against the registry; they have their own endpoint.
            throw new FieldValidationException(Map.of("personRules", "Change Person rules in Setup → Person rules."));
        }
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            int n = jdbc.sql("UPDATE app_setting SET setting_value = CAST(:v AS jsonb) WHERE setting_key = :k")
                    .param("v", json.writeValueAsString(e.getValue())).param("k", e.getKey()).update();
            if (n == 0) {
                throw new NotFoundException("Setting", e.getKey());
            }
        }
        audit.record("app_setting", null, "update", null, changes, null);
    }

    private boolean exists(String setCode, String code) {
        return jdbc.sql("SELECT count(*) FROM picklist_value WHERE set_code = :set AND code = :code")
                .param("set", setCode).param("code", code).query(Long.class).single() > 0;
    }
}
