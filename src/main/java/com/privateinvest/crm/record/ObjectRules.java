package com.privateinvest.crm.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldService;
import com.privateinvest.crm.record.RecordObjects.ObjectType;
import com.privateinvest.crm.record.search.SearchDtos.SortKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rules that use particular fields of a record type: the record name, the picker subtitle, the duplicate check,
 * the unique fields (hard gate G1, e.g. ACN and ABN), the owner ("My records" and the default owner), the type
 * field (what a linked Person inherits from an Entity), the review queue and the default list order. Every field
 * is an ordinary configurable field, so these rules name their fields here (setting "{object}Rules") and admins
 * change them in Setup. A rule whose field is deleted or disabled switches off; {@link #view} reports it.
 */
@Service
@RequiredArgsConstructor
public class ObjectRules {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CustomFieldService customFields;
    private final PermissionService permissions;
    private final AuditService audit;

    public record DuplicateCheck(List<String> nameFields, String emailField, String mobileField, String dateOfBirthField) {
        static DuplicateCheck none() {
            return new DuplicateCheck(List.of(), null, null, null);
        }
    }

    /** Records whose {@code field} holds one of {@code values} wait on the review queue (brief §3.2, §3.3.3). */
    public record ReviewQueue(String field, List<String> values) {
    }

    public record Rules(
            List<String> nameFields,
            List<String> subtitleFields,
            DuplicateCheck duplicateCheck,
            /** Fields no two live records may share (hard gate G1): the save stops and shows the other record. */
            List<String> uniqueFields,
            String ownerField,
            /** The field that classifies the record; a Person reads it through its Entity links (brief §3.4). */
            String typeField,
            ReviewQueue reviewQueue,
            List<SortKey> defaultSort) {
    }

    /** The rules as stored, plus a warning for each rule that is off because its field is missing or disabled. */
    public record RulesView(Rules rules, List<String> warnings) {
    }

    /* ---------- Reading ---------- */

    public static String settingKey(String object) {
        return RecordObjects.get(object).apiName() + "Rules";
    }

    /** The stored rules (fields may since have been deleted or disabled), with empty lists for anything unset. */
    public Rules stored(String object) {
        String text = jdbc.sql("SELECT setting_value::text FROM app_setting WHERE setting_key = :k")
                .param("k", settingKey(object)).query(String.class).optional().orElse(null);
        Rules r = text == null ? null : json.readValue(text, Rules.class);
        if (r == null) {
            return new Rules(List.of(), List.of(), DuplicateCheck.none(), List.of(), null, null, null, List.of());
        }
        DuplicateCheck d = r.duplicateCheck() == null ? DuplicateCheck.none() : r.duplicateCheck();
        return new Rules(nonNull(r.nameFields()), nonNull(r.subtitleFields()),
                new DuplicateCheck(nonNull(d.nameFields()), d.emailField(), d.mobileField(), d.dateOfBirthField()),
                nonNull(r.uniqueFields()), r.ownerField(), r.typeField(), r.reviewQueue(),
                r.defaultSort() == null ? List.of() : r.defaultSort());
    }

    /** The rules with every field that no longer works (missing or disabled) left out: what the CRM applies. */
    public Rules effective(String object) {
        Rules r = stored(object);
        Map<String, CustomFieldDef> active = activeFields(object);
        DuplicateCheck d = r.duplicateCheck();
        ReviewQueue q = r.reviewQueue();
        return new Rules(
                keep(r.nameFields(), active),
                keep(r.subtitleFields(), active),
                new DuplicateCheck(keep(d.nameFields(), active), one(d.emailField(), active), one(d.mobileField(), active),
                        one(d.dateOfBirthField(), active)),
                keep(r.uniqueFields(), active),
                one(r.ownerField(), active),
                one(r.typeField(), active),
                q != null && active.containsKey(q.field()) && q.values() != null && !q.values().isEmpty() ? q : null,
                r.defaultSort().stream().filter(s -> active.containsKey(s.field())).toList());
    }

    public RulesView view(String object) {
        ObjectType type = RecordObjects.get(object);
        Rules r = stored(object);
        Map<String, CustomFieldDef> active = activeFields(object);
        List<String> warnings = new ArrayList<>();
        Predicate<String> off = f -> f != null && !active.containsKey(f);
        if (r.nameFields().stream().anyMatch(off)) {
            warnings.add("Record name: a name field is deleted or disabled, so it is left out of the name.");
        }
        if (r.nameFields().stream().noneMatch(active::containsKey)) {
            warnings.add("Record name: no name field is set, so records show a placeholder name.");
        }
        DuplicateCheck d = r.duplicateCheck();
        if (d.nameFields().stream().anyMatch(off) || off.test(d.emailField()) || off.test(d.mobileField())
                || off.test(d.dateOfBirthField())) {
            warnings.add("Duplicate check: a field it compares is deleted or disabled, so that comparison is off.");
        }
        if (r.uniqueFields().stream().anyMatch(off)) {
            warnings.add("Unique fields: a unique field is deleted or disabled, so that hard gate is off.");
        }
        if (off.test(r.ownerField())) {
            warnings.add("Owner: the owner field is deleted or disabled, so \"My " + type.plural().toLowerCase()
                    + "\" and the default owner are off.");
        }
        if (off.test(r.typeField())) {
            warnings.add("Type: the type field is deleted or disabled, so linked records can't read it.");
        }
        if (r.reviewQueue() != null && off.test(r.reviewQueue().field())) {
            warnings.add("Review queue: its field is deleted or disabled, so the queue is off.");
        }
        return new RulesView(r, warnings);
    }

    /* ---------- Changing ---------- */

    @Transactional
    public RulesView update(String object, Rules r) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "change " + type.singular() + " rules");
        Map<String, CustomFieldDef> active = activeFields(object);
        Map<String, String> errors = new LinkedHashMap<>();
        checkList(errors, "nameFields", r.nameFields(), active, 3, DataType::isText, type);
        checkList(errors, "subtitleFields", r.subtitleFields(), active, 3, t -> t != DataType.LONG_TEXT, type);
        DuplicateCheck d = r.duplicateCheck() == null ? DuplicateCheck.none() : r.duplicateCheck();
        checkList(errors, "duplicateCheck.nameFields", d.nameFields(), active, 3, DataType::isText, type);
        checkOne(errors, "duplicateCheck.emailField", d.emailField(), active,
                t -> t == DataType.EMAIL || t == DataType.EMAIL_LIST, type);
        checkOne(errors, "duplicateCheck.mobileField", d.mobileField(), active,
                t -> t == DataType.PHONE || t == DataType.PHONE_LIST, type);
        checkOne(errors, "duplicateCheck.dateOfBirthField", d.dateOfBirthField(), active, t -> t == DataType.DATE, type);
        checkList(errors, "uniqueFields", r.uniqueFields(), active, 3,
                t -> t == DataType.TEXT || t == DataType.EMAIL || t == DataType.PHONE, type);
        checkOne(errors, "ownerField", r.ownerField(), active, t -> t == DataType.USER, type);
        checkOne(errors, "typeField", r.typeField(), active, DataType::isPicklist, type);
        if (r.reviewQueue() != null && r.reviewQueue().field() != null) {
            checkOne(errors, "reviewQueue.field", r.reviewQueue().field(), active, DataType::isPicklist, type);
            CustomFieldDef f = active.get(r.reviewQueue().field());
            List<String> values = r.reviewQueue().values() == null ? List.of() : r.reviewQueue().values();
            if (values.isEmpty()) {
                errors.put("reviewQueue.values", "Choose at least one value that puts a record on the queue.");
            } else if (f != null && jdbc.sql("SELECT count(DISTINCT code) FROM picklist_value WHERE set_code = :s AND code IN (:c)")
                    .param("s", f.picklist()).param("c", values).query(Long.class).single() != values.stream().distinct().count()) {
                errors.put("reviewQueue.values", "Choose values from " + f.label() + "'s list.");
            }
        }
        List<SortKey> sort = r.defaultSort() == null ? List.of() : r.defaultSort();
        if (sort.size() > 3) {
            errors.put("defaultSort", "Sort by at most 3 fields.");
        }
        for (SortKey s : sort) {
            CustomFieldDef f = active.get(s.field());
            if (f == null || !f.sortable() || f.dataType() == DataType.MULTI_PICKLIST || f.derived()) {
                errors.put("defaultSort", "Sort by enabled fields that can be sorted.");
            } else if (!"ASC".equals(s.dir()) && !"DESC".equals(s.dir())) {
                errors.put("defaultSort", "Sort direction is ASC or DESC.");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        Rules before = stored(object);
        Rules clean = new Rules(nonNull(r.nameFields()), nonNull(r.subtitleFields()),
                new DuplicateCheck(nonNull(d.nameFields()), blank(d.emailField()), blank(d.mobileField()), blank(d.dateOfBirthField())),
                nonNull(r.uniqueFields()),
                blank(r.ownerField()),
                blank(r.typeField()),
                r.reviewQueue() == null || r.reviewQueue().field() == null ? null
                        : new ReviewQueue(r.reviewQueue().field(), nonNull(r.reviewQueue().values())),
                sort);
        jdbc.sql("""
                        INSERT INTO app_setting (setting_key, setting_value) VALUES (:k, CAST(:v AS jsonb))
                        ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value""")
                .param("k", settingKey(object)).param("v", json.writeValueAsString(clean)).update();
        audit.record("app_setting", null, "update-" + type.apiName() + "-rules", before, clean, null);
        return view(object);
    }

    /* ---------- Helpers ---------- */

    private Map<String, CustomFieldDef> activeFields(String object) {
        Map<String, CustomFieldDef> m = new LinkedHashMap<>();
        customFields.list(RecordObjects.get(object).apiName()).stream().filter(CustomFieldDef::active)
                .forEach(f -> m.put(f.apiName(), f));
        return m;
    }

    private static void checkList(Map<String, String> errors, String key, List<String> fields, Map<String, CustomFieldDef> active,
                                  int max, Predicate<DataType> type, ObjectType object) {
        if (fields == null) {
            return;
        }
        if (fields.size() > max) {
            errors.put(key, "Choose at most " + max + " fields.");
        }
        for (String f : fields) {
            checkOne(errors, key, f, active, type, object);
        }
    }

    private static void checkOne(Map<String, String> errors, String key, String field, Map<String, CustomFieldDef> active,
                                 Predicate<DataType> type, ObjectType object) {
        if (field == null || field.isBlank()) {
            return;
        }
        CustomFieldDef f = active.get(field);
        if (f == null) {
            errors.put(key, "'" + field + "' isn't an enabled " + object.singular() + " field.");
        } else if (!type.test(f.dataType()) || f.derived()) {
            errors.put(key, "'" + f.label() + "' is the wrong type for this rule.");
        }
    }

    private static List<String> keep(List<String> fields, Map<String, CustomFieldDef> active) {
        return fields == null ? List.of() : fields.stream().filter(active::containsKey).toList();
    }

    private static String one(String field, Map<String, CustomFieldDef> active) {
        return field != null && active.containsKey(field) ? field : null;
    }

    private static List<String> nonNull(List<String> l) {
        return l == null ? List.of() : l.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
