package com.privateinvest.crm.record;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateBlockException;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateMatch;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateWarningException;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.common.RecordVersion;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldService;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldCreate;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldUpdate;
import com.privateinvest.crm.customfield.RecordFieldService;
import com.privateinvest.crm.record.ObjectRules.Rules;
import com.privateinvest.crm.record.RecordDtos.RecordCreate;
import com.privateinvest.crm.record.RecordDtos.RecordDetail;
import com.privateinvest.crm.record.RecordDtos.RecordExtension;
import com.privateinvest.crm.record.RecordDtos.RecordFlags;
import com.privateinvest.crm.record.RecordDtos.Related;
import com.privateinvest.crm.record.RecordObjects.ObjectType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records of every configurable type (Person, Entity). Every business field is a configurable field written by
 * api name; rules that need particular fields (name, duplicate check, unique gate, owner) come from
 * {@link ObjectRules}. The record row keeps only system state: flags, audit columns, archive and version.
 * Records are never deleted: archiving hides them and can be undone.
 */
@Service
public class RecordService {

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final PermissionService permissions;
    private final CustomFieldService customFields;
    private final RecordFieldService recordFields;
    private final RecordFieldWriter fields;
    private final ObjectRules rules;
    private final RecordQueries queries;
    private final Map<String, RecordExtension> extensions;

    public RecordService(JdbcClient jdbc, AuditService audit, CurrentUser currentUser, PermissionService permissions,
                         CustomFieldService customFields, RecordFieldService recordFields, RecordFieldWriter fields,
                         ObjectRules rules, RecordQueries queries, List<RecordExtension> extensions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
        this.permissions = permissions;
        this.customFields = customFields;
        this.recordFields = recordFields;
        this.fields = fields;
        this.rules = rules;
        this.queries = queries;
        this.extensions = extensions.stream().collect(Collectors.toMap(RecordExtension::object, Function.identity()));
    }

    /** The system columns of one record. */
    private record Row(UUID id, OffsetDateTime hotSince, String possibleDuplicateReason, List<String> hiddenFields,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt, long version) {
    }

    /* ---------- Reading ---------- */

    /** A live record; an archived one only when asked for, by users who may see archived records. */
    @Transactional(readOnly = true)
    public RecordDetail get(String object, UUID id, boolean includeArchived) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.readPermission(), "see " + type.plural());
        Row row = row(type, id, false)
                .filter(r -> r.archivedAt() == null || (includeArchived && permissions.has(type.archivePermission())))
                .orElseThrow(() -> new NotFoundException(type.singular(), id));
        return toDetail(type, row);
    }

    /** Throws unless a live record with this id exists (used by links before they write). */
    public void requireLive(String object, UUID id) {
        ObjectType type = RecordObjects.get(object);
        row(type, id, false).filter(r -> r.archivedAt() == null).orElseThrow(() -> new NotFoundException(type.singular(), id));
    }

    /* ---------- Writing ---------- */

    /**
     * Creates a record from field values. Required fields, types and options come from the field registry. A
     * value already held by another live record in a unique field stops the save (hard gate G1). If the
     * duplicate check finds a match, the save stops with a warning unless the caller gives a reason; the record
     * is then flagged Possible duplicate (brief §3.8.3). The row, its values, one-off fields and the audit row
     * are written in one transaction.
     */
    @Transactional
    public RecordDetail create(String object, RecordCreate c) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.createPermission(), "add " + type.plural());
        Map<String, Object> values = new LinkedHashMap<>();
        if (c.topLevel() != null) {
            values.putAll(c.topLevel());
        }
        if (c.customFields() != null) {
            values.putAll(c.customFields());
        }
        if (c.values() != null) {
            values.putAll(c.values());
        }
        CustomFieldService.Prepared checked = fields.check(type.apiName(), values, true, Map.of(), List.of(), Map.of());
        Rules r = rules.effective(type.apiName());
        queries.checkUnique(type.apiName(), r.uniqueFields(), r.nameFields(), checked.change().after(), null);

        String reason = Formats.clean(c.duplicateReason());
        if (reason == null) {
            List<DuplicateMatch> matches = queries.duplicates(type.apiName(), r.duplicateCheck(), checked.change().after(), null);
            if (!matches.isEmpty()) {
                throw new DuplicateWarningException(matches);
            }
        }
        UUID id = jdbc.sql("""
                        INSERT INTO %s (possible_duplicate_reason, created_at, created_by, updated_at, updated_by)
                        VALUES (:reason, now(), :user, now(), :user) RETURNING id""".formatted(type.table()))
                .param("reason", reason).param("user", currentUser.id())
                .query(UUID.class).single();
        persist(type, id, checked);
        if (c.recordFields() != null) {
            c.recordFields().forEach(f -> recordFields.add(type.apiName(), id, f));
        }
        audit.record(type.apiName(), id, "create", null, checked.change().after(), reason);
        return toDetail(type, row(type, id, false).orElseThrow());
    }

    /** Changes field values by api name. Only the fields sent change; null clears one. */
    @Transactional
    public RecordDetail patch(String object, UUID id, long version, Map<String, Object> values, String action) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.editPermission(), "change " + type.plural());
        Row row = lockLive(type, id);
        RecordVersion.check(row.version(), version);
        Map<String, Object> current = customFields.currentForWrite(type.apiName(), id);
        List<String> hidden = fields.hidden(type.apiName(), row.hiddenFields());
        CustomFieldService.Prepared checked = fields.check(type.apiName(), values, false, current, hidden, context(type, id));
        if (checked.change().isEmpty()) {
            return toDetail(type, row);
        }
        Rules r = rules.effective(type.apiName());
        List<String> changedUnique = r.uniqueFields().stream().filter(checked.change().after()::containsKey).toList();
        queries.checkUnique(type.apiName(), changedUnique, r.nameFields(), checked.change().after(), id);
        persist(type, id, checked);
        // Giving a removed field a value adds it back to this record.
        for (String f : row.hiddenFields()) {
            if (checked.change().after().get(f) != null) {
                setHidden(type, id, f, false);
            }
        }
        return touch(type, id, action, checked.change().before(), checked.change().after());
    }

    /**
     * Removes a field from this record only: its value is deleted and it no longer shows on this record's page
     * or edit form. Other records keep the field. Required fields can't be removed.
     */
    @Transactional
    public RecordDetail removeField(String object, UUID id, String apiName) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.editPermission(), "change " + type.plural());
        Row row = lockLive(type, id);
        CustomFieldDef d = fields.fields(type.apiName()).stream().filter(f -> f.apiName().equals(apiName) && f.active()).findFirst()
                .orElseThrow(() -> new NotFoundException(type.singular() + " field", apiName));
        if (d.mandatory()) {
            throw new FieldValidationException(Map.of(apiName,
                    "'" + d.label() + "' is required for every " + type.singular() + ", so it can't be removed."));
        }
        if (row.hiddenFields().contains(apiName)) {
            return toDetail(type, row);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> current = customFields.currentForWrite(type.apiName(), id);
        if (!d.computed() && current.get(apiName) != null) {
            Map<String, Object> clear = new LinkedHashMap<>();
            clear.put(apiName, null);
            CustomFieldService.Prepared checked = customFields.prepare(type.apiName(), current, clear, false, java.util.Set.of());
            if (!checked.errors().isEmpty()) {
                throw new FieldValidationException(checked.errors());
            }
            persist(type, id, checked);
            before.put(apiName, current.get(apiName));
        }
        setHidden(type, id, apiName, true);
        return touch(type, id, "remove-field", before, Map.of("removed", apiName));
    }

    /** Adds a field removed from this record back to it (empty, ready to fill in). */
    @Transactional
    public RecordDetail addFieldBack(String object, UUID id, String apiName) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.editPermission(), "change " + type.plural());
        Row row = lockLive(type, id);
        if (!row.hiddenFields().contains(apiName)) {
            return toDetail(type, row);
        }
        setHidden(type, id, apiName, false);
        return touch(type, id, "add-field-back", null, Map.of("added", apiName));
    }

    /** Archives (soft-deletes) a record: hidden from lists, search and pickers; data and history are kept. */
    @Transactional
    public RecordDetail archive(String object, UUID id, long version, String reason) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.archivePermission(), "archive " + type.plural());
        Row row = lockLive(type, id);
        RecordVersion.check(row.version(), version);
        String clean = Formats.clean(reason);
        jdbc.sql("UPDATE %s SET archived_at = now(), archived_by = :u, archive_reason = :r WHERE id = :id".formatted(type.table()))
                .param("u", currentUser.id()).param("r", clean).param("id", id).update();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("reason", clean);
        return touch(type, id, "archive", null, change);
    }

    /**
     * Brings an archived record back. The unique gate and the duplicate check run again, as for a new record:
     * another live record may have taken its ACN in the meantime.
     */
    @Transactional
    public RecordDetail restore(String object, UUID id, long version, String duplicateReason) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.archivePermission(), "restore " + type.plural());
        Row row = row(type, id, true).filter(r -> r.archivedAt() != null)
                .orElseThrow(() -> new NotFoundException("Archived " + type.singular(), id));
        RecordVersion.check(row.version(), version);
        Map<String, Object> current = customFields.currentForWrite(type.apiName(), id);
        Rules r = rules.effective(type.apiName());
        queries.checkUnique(type.apiName(), r.uniqueFields(), r.nameFields(), current, id);
        String reason = Formats.clean(duplicateReason);
        if (reason == null) {
            List<DuplicateMatch> matches = queries.duplicates(type.apiName(), r.duplicateCheck(), current, id);
            if (!matches.isEmpty()) {
                throw new DuplicateWarningException(matches);
            }
        }
        jdbc.sql("""
                        UPDATE %s SET archived_at = NULL, archived_by = NULL, archive_reason = NULL,
                            possible_duplicate_reason = COALESCE(:reason, possible_duplicate_reason)
                        WHERE id = :id""".formatted(type.table()))
                .param("reason", reason).param("id", id).update();
        return touch(type, id, "restore", null, reason == null ? null : Map.of("duplicateReason", reason));
    }

    /** Hot moves a record to the top of lists and clears itself after the Hot expiry setting (brief §3.8.2). */
    @Transactional
    public RecordDetail setHot(String object, UUID id, boolean hot) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.editPermission(), "change " + type.plural());
        Row row = lockLive(type, id);
        jdbc.sql("UPDATE %s SET hot_since = CASE WHEN :hot THEN now() END WHERE id = :id".formatted(type.table()))
                .param("hot", hot).param("id", id).update();
        return touch(type, id, hot ? "flag-hot" : "clear-hot", Map.of("hotSince", String.valueOf(row.hotSince())), Map.of("hot", hot));
    }

    /* ---------- One-off fields (this record only) ---------- */

    @Transactional
    public RecordDetail addRecordField(String object, UUID id, RecordFieldCreate c) {
        ObjectType type = editable(object, id);
        return touch(type, id, "add-record-field", null, recordFields.add(type.apiName(), id, c));
    }

    @Transactional
    public RecordDetail updateRecordField(String object, UUID id, UUID fieldId, RecordFieldUpdate u) {
        ObjectType type = editable(object, id);
        Map<String, Object> change = recordFields.update(type.apiName(), id, fieldId, u);
        return touch(type, id, "update-record-field", change.get("before"), change.get("after"));
    }

    @Transactional
    public RecordDetail removeRecordField(String object, UUID id, UUID fieldId) {
        ObjectType type = editable(object, id);
        return touch(type, id, "remove-record-field", recordFields.remove(type.apiName(), id, fieldId), null);
    }

    /**
     * Adds a value to a record's phone or email list when it isn't there yet (a single phone or email field is
     * set only when empty). Used when an email typed at an Entity link becomes the Person's (brief §3.2).
     */
    @Transactional
    public void ensureListValue(String object, UUID id, String field, String value, String label) {
        ObjectType type = RecordObjects.get(object);
        if (field == null || Formats.isBlank(value)) {
            return;
        }
        lockLive(type, id);
        CustomFieldDef d = fields.fields(type.apiName()).stream().filter(f -> f.apiName().equals(field) && f.active())
                .findFirst().orElse(null);
        if (d == null) {
            return;
        }
        Map<String, Object> current = customFields.currentForWrite(type.apiName(), id);
        Object now = current.get(field);
        Object next;
        if (d.dataType().isList()) {
            boolean phone = d.dataType() == DataType.PHONE_LIST;
            boolean present = CustomFieldService.listValues(now, null).stream()
                    .anyMatch(v -> phone ? Formats.digits(v).equals(Formats.digits(value)) : v.equalsIgnoreCase(value.trim()));
            if (present) {
                return;
            }
            List<Object> items = now instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label);
            item.put("value", value.trim());
            item.put("primary", items.isEmpty());
            items.add(item);
            next = items;
        } else if (CustomFieldService.isEmpty(now)) {
            next = value.trim();
        } else {
            return;
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(field, next);
        CustomFieldService.Prepared checked = customFields.prepare(type.apiName(), current, patch, false, java.util.Set.of());
        if (checked.errors().containsKey(field)) {
            throw new FieldValidationException(Map.of("email", checked.errors().get(field)));
        }
        if (!checked.change().isEmpty()) {
            persist(type, id, checked);
            touch(type, id, "add-" + field, null, Map.of(field, value, "via", "entity-link"));
        }
    }

    /* ---------- Helpers ---------- */

    private ObjectType editable(String object, UUID id) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.editPermission(), "change " + type.plural());
        lockLive(type, id);
        return type;
    }

    /** Writes field values; the unique indexes on ACN and ABN are the final guard if two saves race past G1. */
    private void persist(ObjectType type, UUID id, CustomFieldService.Prepared checked) {
        try {
            customFields.persist(id, checked);
        } catch (DuplicateKeyException ex) {
            String field = String.valueOf(ex.getMessage()).contains("abn") ? "ABN" : "ACN";
            throw new DuplicateBlockException(field, null, "another " + type.singular(), type.plural());
        }
    }

    private void setHidden(ObjectType type, UUID id, String apiName, boolean hide) {
        jdbc.sql((hide
                ? "UPDATE %s SET hidden_fields = array_append(array_remove(hidden_fields, :f), :f) WHERE id = :id"
                : "UPDATE %s SET hidden_fields = array_remove(hidden_fields, :f) WHERE id = :id").formatted(type.table()))
                .param("f", apiName).param("id", id).update();
    }

    /** Locks the row for this transaction, so concurrent saves of the same record run one after the other. */
    private Row lockLive(ObjectType type, UUID id) {
        return row(type, id, true).filter(r -> r.archivedAt() == null)
                .orElseThrow(() -> new NotFoundException(type.singular(), id));
    }

    private java.util.Optional<Row> row(ObjectType type, UUID id, boolean lock) {
        return jdbc.sql("""
                        SELECT id, hot_since, possible_duplicate_reason, hidden_fields, created_at, updated_at, archived_at, version
                        FROM %s WHERE id = :id%s""".formatted(type.table(), lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("hot_since", OffsetDateTime.class),
                        rs.getString("possible_duplicate_reason"), strings(rs.getArray("hidden_fields")),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("archived_at", OffsetDateTime.class), rs.getLong("version")))
                .optional();
    }

    private RecordDetail touch(ObjectType type, UUID id, String action, Object before, Object after) {
        jdbc.sql("UPDATE %s SET updated_at = now(), updated_by = :u, version = version + 1 WHERE id = :id".formatted(type.table()))
                .param("u", currentUser.id()).param("id", id).update();
        audit.record(type.apiName(), id, action, before, after, null);
        return toDetail(type, row(type, id, false).orElseThrow());
    }

    private Map<String, Object> context(ObjectType type, UUID id) {
        RecordExtension ext = extensions.get(type.apiName());
        return ext == null ? Map.of() : ext.related(id).context();
    }

    private RecordDetail toDetail(ObjectType type, Row row) {
        RecordExtension ext = extensions.get(type.apiName());
        Related related = ext == null ? Related.NONE : ext.related(row.id());
        Map<String, Object> values = fields.values(type.apiName(), customFields.read(type.apiName(), row.id()));
        List<String> hidden = fields.hidden(type.apiName(), row.hiddenFields());
        return new RecordDetail(
                row.id(),
                type.apiName(),
                fields.title(type.apiName(), values),
                values,
                recordFields.list(type.apiName(), row.id()),
                fields.dataGaps(type.apiName(), values, hidden, related.context()),
                hidden,
                new RecordFlags(row.hotSince(), row.possibleDuplicateReason()),
                related.context(),
                row.archivedAt() != null,
                row.archivedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.version(),
                related.links(),
                related.persons(),
                related.inheritedEntityType());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
