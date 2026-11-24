package com.privateinvest.crm.customfield;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordField;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldCreate;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldUpdate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-off fields on a single Person or Entity, stored in the record's record_fields JSONB column keyed by
 * field id. Values use the same type rules as shared custom fields. Removing a field archives it (removedAt);
 * nothing is deleted. Each change locks the record's row, so labels stay unique and edits never interleave.
 * Callers audit the change on the record itself.
 */
@Service
@RequiredArgsConstructor
public class RecordFieldService {

    private static final Map<String, String> RECORD_TABLE = Map.of("person", "person", "entity", "entity");
    /** Dropdowns and checkboxes need option lists or "unticked" semantics, which one-off fields don't have. */
    private static final Set<DataType> ALLOWED = Set.of(DataType.TEXT, DataType.LONG_TEXT, DataType.NUMBER,
            DataType.CURRENCY, DataType.PERCENT, DataType.DATE, DataType.BOOLEAN, DataType.EMAIL, DataType.PHONE, DataType.URL);
    private static final int MAX_PER_RECORD = 50;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CustomFieldService customFields;
    private final CurrentUser currentUser;
    /** {@link #json} reading decimals exactly; built on first use. */
    private volatile JsonMapper exactJson;

    public List<RecordField> list(String object, UUID recordId) {
        String document = jdbc.sql("SELECT record_fields::text FROM %s WHERE id = :r".formatted(table(object)))
                .param("r", recordId).query(String.class).optional().orElse("{}");
        return active(read(document));
    }

    /** Adds a one-off field with its value. Returns what was added, for the caller's audit. */
    @Transactional
    public Map<String, Object> add(String object, UUID recordId, RecordFieldCreate c) {
        Map<String, Map<String, Object>> doc = lockAndRead(object, recordId);
        String label = c.label().trim();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!ALLOWED.contains(c.dataType())) {
            errors.put("dataType", "One-off fields can be text, number, currency, percent, date, Yes/No, email, phone or web address.");
        }
        checkLabel(errors, object, doc, label, null);
        if (active(doc).size() >= MAX_PER_RECORD) {
            errors.put("label", "This record already has " + MAX_PER_RECORD + " one-off fields. Remove one first.");
        }
        Object value = errors.isEmpty() ? required(c.dataType(), c.value(), errors) : null;
        throwIfAny(errors);

        int sort = doc.values().stream().mapToInt(f -> ((Number) f.get("sortOrder")).intValue()).max().orElse(-1) + 1;
        String now = OffsetDateTime.now().toString();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", label);
        field.put("dataType", c.dataType().name());
        field.put("value", value);
        field.put("sortOrder", sort);
        field.put("version", 0);
        field.put("createdAt", now);
        field.put("createdBy", currentUser.id());
        field.put("updatedAt", now);
        field.put("updatedBy", currentUser.id());
        doc.put(UUID.randomUUID().toString(), field);
        save(object, recordId, doc);
        return Map.of("label", label, "dataType", c.dataType().name(), "value", value);
    }

    /** Changes a one-off field's label or value. Returns before/after for the caller's audit. */
    @Transactional
    public Map<String, Object> update(String object, UUID recordId, UUID fieldId, RecordFieldUpdate u) {
        Map<String, Map<String, Object>> doc = lockAndRead(object, recordId);
        RecordField current = find(doc, fieldId);
        if (current.version() != u.version()) {
            throw new OptimisticLockingFailureException("One-off field " + fieldId + " changed");
        }
        String label = u.label().trim();
        Map<String, String> errors = new LinkedHashMap<>();
        checkLabel(errors, object, doc, label, fieldId);
        Object value = required(current.dataType(), u.value(), errors);
        throwIfAny(errors);

        Map<String, Object> field = doc.get(fieldId.toString());
        field.put("label", label);
        field.put("value", value);
        field.put("version", current.version() + 1);
        field.put("updatedAt", OffsetDateTime.now().toString());
        field.put("updatedBy", currentUser.id());
        save(object, recordId, doc);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", Map.of("label", current.label(), "value", current.value()));
        change.put("after", Map.of("label", label, "value", value));
        return change;
    }

    /** Archives a one-off field; it disappears from the record but stays in the document and audit trail. */
    @Transactional
    public Map<String, Object> remove(String object, UUID recordId, UUID fieldId) {
        Map<String, Map<String, Object>> doc = lockAndRead(object, recordId);
        RecordField current = find(doc, fieldId);
        Map<String, Object> field = doc.get(fieldId.toString());
        field.put("removedAt", OffsetDateTime.now().toString());
        field.put("removedBy", currentUser.id());
        save(object, recordId, doc);
        return Map.of("label", current.label(), "value", current.value());
    }

    /* ---------- Helpers ---------- */

    /** Locks the record's row for this transaction and returns its one-off fields, removed ones included. */
    private Map<String, Map<String, Object>> lockAndRead(String object, UUID recordId) {
        String document = jdbc.sql("SELECT record_fields::text FROM %s WHERE id = :r FOR UPDATE".formatted(table(object)))
                .param("r", recordId).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("Record", recordId));
        return read(document);
    }

    private void save(String object, UUID recordId, Map<String, Map<String, Object>> doc) {
        jdbc.sql("UPDATE %s SET record_fields = CAST(:doc AS jsonb) WHERE id = :r".formatted(table(object)))
                .param("doc", json.writeValueAsString(doc)).param("r", recordId).update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> read(String document) {
        if (exactJson == null) {
            exactJson = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        }
        return exactJson.readValue(document, LinkedHashMap.class);
    }

    /** Fields that haven't been removed, in display order. */
    private static List<RecordField> active(Map<String, Map<String, Object>> doc) {
        return doc.entrySet().stream()
                .filter(e -> e.getValue().get("removedAt") == null)
                .sorted(Comparator.comparingInt((Map.Entry<String, Map<String, Object>> e) ->
                                ((Number) e.getValue().get("sortOrder")).intValue())
                        .thenComparing(e -> String.valueOf(e.getValue().get("createdAt"))))
                .map(e -> toField(e.getKey(), e.getValue()))
                .toList();
    }

    private static RecordField toField(String id, Map<String, Object> f) {
        DataType type = DataType.valueOf((String) f.get("dataType"));
        Object value = type.isNumeric() ? plain(new BigDecimal(f.get("value").toString())) : f.get("value");
        return new RecordField(UUID.fromString(id), (String) f.get("label"), type, value,
                ((Number) f.get("sortOrder")).intValue(), ((Number) f.get("version")).longValue());
    }

    private static RecordField find(Map<String, Map<String, Object>> doc, UUID fieldId) {
        return active(doc).stream().filter(f -> f.id().equals(fieldId)).findFirst()
                .orElseThrow(() -> new NotFoundException("One-off field", fieldId));
    }

    /** A one-off field must hold a value of its type. */
    private Object required(DataType type, Object raw, Map<String, String> errors) {
        Object value = customFields.normalizeForType(type, raw, errors, "value");
        if (value == null && !errors.containsKey("value")) {
            errors.put("value", "Enter a value, or remove the field instead.");
        }
        return value;
    }

    /** Labels are unique on the record, and mustn't repeat a shared field's label (use that field instead). */
    private void checkLabel(Map<String, String> errors, String object, Map<String, Map<String, Object>> doc,
                            String label, UUID ignoreId) {
        boolean onRecord = active(doc).stream()
                .anyMatch(f -> !f.id().equals(ignoreId) && f.label().equalsIgnoreCase(label));
        if (onRecord) {
            errors.put("label", "This record already has a field called '" + label + "'.");
            return;
        }
        boolean shared = jdbc.sql("""
                        SELECT count(*) FROM custom_field_def
                        WHERE object_api_name = :obj AND active AND lower(label) = lower(:label)""")
                .param("obj", object).param("label", label).query(Long.class).single() > 0;
        if (shared) {
            errors.put("label", "'" + label + "' is already a field for every " + object + ". Fill that in under Additional details.");
        }
    }

    private static BigDecimal plain(BigDecimal n) {
        if (n == null) {
            return null;
        }
        BigDecimal s = n.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private static String table(String object) {
        String table = RECORD_TABLE.get(object);
        if (table == null) {
            throw new NotFoundException("Record type", object);
        }
        return table;
    }
}
