package com.privateinvest.crm.record;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateBlockException;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateMatch;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldService;
import com.privateinvest.crm.record.ObjectRules.DuplicateCheck;
import com.privateinvest.crm.record.RecordObjects.ObjectType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-side SQL shared by every record type: duplicate warnings, the unique-field gate, and expressions that read
 * a field's value (the primary item of a list) by api name. Field keys are always bound parameters; table names
 * come from {@link RecordObjects}, never from a request.
 */
@Component
@RequiredArgsConstructor
public class RecordQueries {

    /** Trigram similarity at or above this counts as "similar name" (PostgreSQL pg_trgm). */
    private static final double SIMILAR_NAME = 0.5;
    /** The label code of a mobile number in a phone list: the duplicate check compares mobiles only. */
    public static final String MOBILE_LABEL = "MOBILE";

    private final JdbcClient jdbc;
    private final CustomFieldService customFields;

    /**
     * Duplicate warnings (brief §3.8.3): same email, same mobile, same name and date of birth, or a similar name,
     * using the fields the duplicate-check rule names. A comparison whose field isn't set is skipped. For lists,
     * any email matches any email, and any mobile any mobile. {@code self} leaves the record itself out.
     */
    public List<DuplicateMatch> duplicates(String object, DuplicateCheck rule, Map<String, Object> values, UUID self) {
        ObjectType type = RecordObjects.get(object);
        Map<String, CustomFieldDef> defs = defs(type);
        Map<String, Object> params = new HashMap<>();
        String nameExpr = nameExpression("p", rule.nameFields(), params, "dn");
        String name = rule.nameFields().stream().map(values::get).filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf).reduce((a, b) -> a + " " + b).orElse("");
        params.put("name", name);
        params.put("similar", SIMILAR_NAME);
        params.put("self", self);

        String sameEmail = "FALSE";
        CustomFieldDef emailDef = rule.emailField() == null ? null : defs.get(rule.emailField());
        List<String> emails = emailDef == null ? List.of() : CustomFieldService.listValues(values.get(emailDef.apiName()), null)
                .stream().map(String::toLowerCase).toList();
        if (!emails.isEmpty()) {
            params.put("emailKey", emailDef.apiName());
            params.put("emails", emails);
            sameEmail = emailDef.dataType() == DataType.EMAIL_LIST
                    ? "EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p.custom_fields -> :emailKey) = 'array' "
                    + "THEN p.custom_fields -> :emailKey END) it WHERE lower(it ->> 'value') IN (:emails))"
                    : "lower(p.custom_fields ->> :emailKey) IN (:emails)";
        }
        String sameMobile = "FALSE";
        CustomFieldDef mobileDef = rule.mobileField() == null ? null : defs.get(rule.mobileField());
        boolean mobileList = mobileDef != null && mobileDef.dataType() == DataType.PHONE_LIST;
        List<String> mobiles = mobileDef == null ? List.of()
                : CustomFieldService.listValues(values.get(mobileDef.apiName()), mobileList ? Set.of(MOBILE_LABEL) : null)
                .stream().map(Formats::digits).filter(d -> !d.isEmpty()).toList();
        if (!mobiles.isEmpty()) {
            params.put("mobileKey", mobileDef.apiName());
            params.put("mobiles", mobiles);
            params.put("mobileLabel", MOBILE_LABEL);
            sameMobile = mobileList
                    ? "EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p.custom_fields -> :mobileKey) = 'array' "
                    + "THEN p.custom_fields -> :mobileKey END) it WHERE it ->> 'label' = :mobileLabel "
                    + "AND regexp_replace(it ->> 'value', '\\D', '', 'g') IN (:mobiles))"
                    : "regexp_replace(p.custom_fields ->> :mobileKey, '\\D', '', 'g') IN (:mobiles)";
        }
        String dob = rule.dateOfBirthField() == null || values.get(rule.dateOfBirthField()) == null ? ""
                : String.valueOf(values.get(rule.dateOfBirthField())).trim();
        String sameNameDob = "FALSE";
        if (!dob.isEmpty() && !name.isEmpty()) {
            sameNameDob = "p.custom_fields ->> :dobKey = :dob AND lower(" + nameExpr + ") = lower(:name)";
            params.put("dobKey", rule.dateOfBirthField());
            params.put("dob", dob);
        }
        // Entities compare names without suffixes such as "Pty Ltd" (entity_name_key, migration V4).
        String similar = name.isEmpty() ? "FALSE" : type.companyNames()
                ? "similarity(entity_name_key(" + nameExpr + "), entity_name_key(:name)) >= :similar"
                : "similarity(" + nameExpr + ", :name) >= :similar";
        return jdbc.sql("""
                        SELECT * FROM (
                          SELECT p.id, %s AS name, %s AS same_email, %s AS same_mobile, %s AS same_name_dob,
                                 %s AS similar_name
                          FROM %s p
                          WHERE p.archived_at IS NULL AND (CAST(:self AS uuid) IS NULL OR p.id <> CAST(:self AS uuid))
                        ) d
                        WHERE same_email OR same_mobile OR same_name_dob OR similar_name
                        ORDER BY name
                        LIMIT 10""".formatted(nameExpr, sameEmail, sameMobile, sameNameDob, similar, type.table()))
                .params(params)
                .query((rs, i) -> {
                    List<String> reasons = new ArrayList<>();
                    if (rs.getBoolean("same_email")) {
                        reasons.add("Same email");
                    }
                    if (rs.getBoolean("same_mobile")) {
                        reasons.add("Same mobile");
                    }
                    if (rs.getBoolean("same_name_dob")) {
                        reasons.add("Same name and date of birth");
                    } else if (rs.getBoolean("similar_name")) {
                        reasons.add("Similar name");
                    }
                    String n = rs.getString("name");
                    return new DuplicateMatch(rs.getObject("id", UUID.class), n == null || n.isBlank() ? "(No name)" : n, reasons);
                })
                .list();
    }

    /**
     * Hard gate G1 (brief §3.8.3): another live record holding the same value in a unique field (ACN, ABN) stops
     * the save; the error names the field and the record that already has it. Values are compared as stored
     * (ABN and ACN are stored as digits).
     */
    public void checkUnique(String object, List<String> uniqueFields, List<String> nameFields, Map<String, Object> values,
                            UUID self) {
        ObjectType type = RecordObjects.get(object);
        Map<String, CustomFieldDef> defs = defs(type);
        for (String field : uniqueFields) {
            Object v = values.get(field);
            CustomFieldDef d = defs.get(field);
            if (d == null || CustomFieldService.isEmpty(v)) {
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            String nameExpr = nameExpression("p", nameFields, params, "n");
            params.put("k", field);
            params.put("v", String.valueOf(v));
            Optional<Map.Entry<UUID, String>> hit = jdbc.sql("""
                            SELECT p.id, %s AS name FROM %s p
                            WHERE p.archived_at IS NULL AND p.custom_fields ->> :k = :v
                              AND (CAST(:self AS uuid) IS NULL OR p.id <> CAST(:self AS uuid))
                            LIMIT 1""".formatted(nameExpr, type.table()))
                    .params(params)
                    .param("self", self, Types.OTHER)
                    .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), String.valueOf(rs.getString("name"))))
                    .optional();
            if (hit.isPresent()) {
                String name = hit.get().getValue().isBlank() ? "another " + type.singular() : hit.get().getValue();
                throw new DuplicateBlockException(d.label(), hit.get().getKey(), name, type.plural());
            }
        }
    }

    /**
     * The record-name fields joined by spaces, e.g. concat_ws(' ', p.first, p.last). Keys are bound parameters
     * ({@code prefix}0, {@code prefix}1...); no field names are written into the SQL.
     */
    public static String nameExpression(String alias, List<String> fields, Map<String, Object> params, String prefix) {
        if (fields.isEmpty()) {
            return "''";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            params.put(prefix + i, fields.get(i));
            parts.add(alias + ".custom_fields ->> :" + prefix + i);
        }
        return "concat_ws(' ', " + String.join(", ", parts) + ")";
    }

    /**
     * A field's value as text: the value itself, or the primary item's value for a phone or email list. NULL
     * when the field is unset. The key is bound as {@code :param}.
     */
    public static String valueExpression(String alias, CustomFieldDef d, Map<String, Object> params, String param) {
        if (d == null) {
            return "NULL";
        }
        params.put(param, d.apiName());
        if (d.dataType().isList()) {
            return "(SELECT it ->> 'value' FROM jsonb_array_elements(CASE WHEN jsonb_typeof(" + alias + ".custom_fields -> :"
                    + param + ") = 'array' THEN " + alias + ".custom_fields -> :" + param
                    + " END) it WHERE (it ->> 'primary')::boolean LIMIT 1)";
        }
        return alias + ".custom_fields ->> :" + param;
    }

    public Map<String, CustomFieldDef> defs(ObjectType type) {
        Map<String, CustomFieldDef> m = new HashMap<>();
        customFields.list(type.apiName()).stream().filter(CustomFieldDef::active).forEach(d -> m.put(d.apiName(), d));
        return m;
    }
}
