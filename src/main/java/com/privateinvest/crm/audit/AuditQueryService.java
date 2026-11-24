package com.privateinvest.crm.audit;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditDtos.AuditChange;
import com.privateinvest.crm.audit.AuditDtos.AuditEntry;
import com.privateinvest.crm.audit.AuditDtos.AuditPage;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.record.RecordObjects;
import com.privateinvest.crm.record.RecordObjects.ObjectType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-side service for record history and the admin audit log (brief §6.4).
 * Enforces object read permissions, archived-record visibility, and sensitive-field redaction.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final String REDACTED_MASK = "••••••";
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> IGNORED_METADATA_KEYS = Set.of(
            "id", "version", "createdAt", "createdBy", "updatedAt", "updatedBy", "deletedAt", "deletedBy", "system", "deletable");
    private static final Set<String> NON_FIELD_DETAIL_KEYS = Set.of(
            "personId", "entityId", "via", "reason", "duplicateReason", "recordsWithValue",
            "archivedRecordsWithValue", "valuesDeleted", "deleted", "added", "removed", "hot", "hotSince",
            "object", "fieldIds", "picklistDeleted", "apiName");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PermissionService permissions;

    private record RawRow(long id, String recordKind, UUID recordId, String action, String changesJson,
            String reason, String userId, OffsetDateTime occurredAt) {
    }

    private record UserMeta(String name, String initials) {
    }

    private record FieldMeta(String label, boolean sensitive) {
    }

    private record Existence(boolean archived) {
    }

    /** History for one Person or Entity, including link events involving it. */
    @Transactional(readOnly = true)
    public AuditPage forRecord(String object, UUID id, int page, int size) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.readPermission(), "see " + type.plural());

        Existence record = jdbc.sql("SELECT archived_at IS NOT NULL AS archived FROM %s WHERE id = :id".formatted(type.table()))
                .param("id", id)
                .query((rs, i) -> new Existence(rs.getBoolean("archived")))
                .optional()
                .orElseThrow(() -> new NotFoundException(type.singular(), id));
        if (record.archived() && !permissions.has(type.archivePermission())) {
            throw new NotFoundException(type.singular(), id);
        }

        String linkJsonKey = "person".equals(type.apiName()) ? "personId" : "entityId";
        String linkColumn = "person".equals(type.apiName()) ? "person_id" : "entity_id";
        String where = """
                (record_kind = :kind AND record_id = :id)
                OR (record_kind = 'entity_link' AND (
                       COALESCE(changes->'after'->>:linkKey, changes->'before'->>:linkKey) = :idText
                    OR record_id IN (SELECT id FROM entity_link WHERE %s = :id)
                ))""".formatted(linkColumn);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kind", type.apiName());
        params.put("id", id);
        params.put("linkKey", linkJsonKey);
        params.put("idText", id.toString());

        return executePage(where, params, page, size, type.apiName(), id);
    }

    /** Global admin audit log with optional filters across all audited record kinds. */
    @Transactional(readOnly = true)
    public AuditPage search(String recordKind, UUID recordId, String action, String userId, int page, int size) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "see the audit log");

        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        String cleanKind = Formats.clean(recordKind);
        if (cleanKind != null) {
            where.append(" AND record_kind = :recordKind");
            params.put("recordKind", cleanKind);
        }
        if (recordId != null) {
            where.append(" AND record_id = :recordId");
            params.put("recordId", recordId);
        }
        String cleanAction = Formats.clean(action);
        if (cleanAction != null) {
            where.append(" AND action = :action");
            params.put("action", cleanAction);
        }
        String cleanUser = Formats.clean(userId);
        if (cleanUser != null) {
            where.append(" AND user_id = :userId");
            params.put("userId", cleanUser);
        }

        return executePage(where.toString(), params, page, size, null, null);
    }

    private AuditPage executePage(String whereClause, Map<String, Object> params, int page, int size,
            String scopeObject, UUID scopeRecordId) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int offset = safePage * safeSize;

        JdbcClient.StatementSpec countSpec = jdbc.sql("SELECT count(*) FROM audit_event WHERE " + whereClause);
        for (var e : params.entrySet()) {
            countSpec = countSpec.param(e.getKey(), e.getValue());
        }
        long total = countSpec.query(Long.class).single();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);

        if (total == 0 || offset >= total) {
            return new AuditPage(List.of(), safePage, safeSize, total, totalPages);
        }

        JdbcClient.StatementSpec rowsSpec = jdbc.sql("""
                SELECT id, record_kind, record_id, action, changes::text AS changes_json, reason, user_id, occurred_at
                FROM audit_event
                WHERE %s
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit OFFSET :offset""".formatted(whereClause));
        for (var e : params.entrySet()) {
            rowsSpec = rowsSpec.param(e.getKey(), e.getValue());
        }
        List<RawRow> rows = rowsSpec.param("limit", safeSize).param("offset", offset)
                .query((rs, i) -> new RawRow(
                        rs.getLong("id"),
                        rs.getString("record_kind"),
                        rs.getObject("record_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("changes_json"),
                        rs.getString("reason"),
                        rs.getString("user_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();

        boolean canSensitive = permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE);
        Map<String, UserMeta> users = loadUsers();
        Map<String, FieldMeta> fields = loadFieldMeta();
        Map<UUID, String> fieldLabelsById = loadFieldLabelsById();

        List<ParsedRow> parsed = rows.stream().map(r -> parseRow(r)).toList();
        Map<UUID, String> personNames = loadRecordNames("person", collectRecordIds(parsed, "person", "personId"));
        Map<UUID, String> entityNames = loadRecordNames("entity", collectRecordIds(parsed, "entity", "entityId"));

        List<AuditEntry> entries = parsed.stream()
                .map(p -> toEntry(p, canSensitive, users, fields, fieldLabelsById, personNames, entityNames, scopeObject, scopeRecordId))
                .toList();

        return new AuditPage(entries, safePage, safeSize, total, totalPages);
    }

    private record ParsedRow(RawRow raw, Map<String, Object> before, Map<String, Object> after) {
    }

    private ParsedRow parseRow(RawRow row) {
        Map<String, Object> root = parseJsonMap(row.changesJson());
        return new ParsedRow(row, asMap(root.get("before")), asMap(root.get("after")));
    }

    private AuditEntry toEntry(
            ParsedRow p,
            boolean canSensitive,
            Map<String, UserMeta> users,
            Map<String, FieldMeta> fields,
            Map<UUID, String> fieldLabelsById,
            Map<UUID, String> personNames,
            Map<UUID, String> entityNames,
            String scopeObject,
            UUID scopeRecordId) {

        RawRow r = p.raw();
        UserMeta user = users.getOrDefault(r.userId(), new UserMeta(r.userId(), initials(r.userId())));
        Map<String, Object> details = new LinkedHashMap<>();
        List<AuditChange> changes = new ArrayList<>();
        String targetLabel = null;
        String reason = Formats.clean(r.reason());

        switch (r.recordKind()) {
            case "person", "entity" -> {
                targetLabel = "person".equals(r.recordKind()) ? personNames.get(r.recordId()) : entityNames.get(r.recordId());
                if (reason == null) {
                    reason = Formats.clean(stringValue(p.after().get("reason")));
                }
                if (reason == null) {
                    reason = Formats.clean(stringValue(p.after().get("duplicateReason")));
                }
                buildRecordChanges(r, p.before(), p.after(), canSensitive, fields, changes, details);
            }
            case "entity_link" -> {
                UUID personId = parseUuid(firstNonNull(p.after().get("personId"), p.before().get("personId")));
                UUID entityId = parseUuid(firstNonNull(p.after().get("entityId"), p.before().get("entityId")));
                String personName = personId == null ? null : personNames.get(personId);
                String entityName = entityId == null ? null : entityNames.get(entityId);
                if (personId != null) {
                    details.put("personId", personId);
                    if (personName != null) {
                        details.put("personName", personName);
                    }
                }
                if (entityId != null) {
                    details.put("entityId", entityId);
                    if (entityName != null) {
                        details.put("entityName", entityName);
                    }
                }
                if ("person".equals(scopeObject) && Objects.equals(scopeRecordId, personId)) {
                    targetLabel = entityName;
                } else if ("entity".equals(scopeObject) && Objects.equals(scopeRecordId, entityId)) {
                    targetLabel = personName;
                } else if (personName != null && entityName != null) {
                    targetLabel = personName + " ↔ " + entityName;
                } else {
                    targetLabel = firstNonNullString(entityName, personName);
                }
                buildLinkChanges(r.action(), p.before(), p.after(), changes, details);
            }
            case "custom_field_def" -> {
                targetLabel = firstNonNullString(
                        stringValue(p.after().get("label")),
                        stringValue(p.before().get("label")),
                        r.recordId() == null ? null : fieldLabelsById.get(r.recordId()));
                buildCustomFieldChanges(r.action(), p.before(), p.after(), changes, details);
            }
            default -> buildGenericChanges(p.before(), p.after(), changes, details);
        }

        return new AuditEntry(
                r.id(),
                r.recordKind(),
                r.recordId(),
                r.action(),
                targetLabel,
                changes,
                details,
                reason,
                r.userId(),
                user.name(),
                user.initials(),
                r.occurredAt());
    }

    private void buildRecordChanges(RawRow row, Map<String, Object> before, Map<String, Object> after,
            boolean canSensitive, Map<String, FieldMeta> fields, List<AuditChange> changes, Map<String, Object> details) {

        String action = row.action();
        String kind = row.recordKind();

        if ("add-record-field".equals(action) || "update-record-field".equals(action) || "remove-record-field".equals(action)) {
            String label = firstNonNullString(stringValue(after.get("label")), stringValue(before.get("label")), "One-off field");
            Object bVal = before.get("value");
            Object aVal = after.get("value");
            if (!Objects.equals(bVal, aVal) || "add-record-field".equals(action) || "remove-record-field".equals(action)) {
                changes.add(new AuditChange("recordField", label, bVal, aVal, false, false));
            }
            String bLabel = stringValue(before.get("label"));
            String aLabel = stringValue(after.get("label"));
            if (bLabel != null && aLabel != null && !Objects.equals(bLabel, aLabel)) {
                changes.add(new AuditChange("recordFieldLabel", "Field label", bLabel, aLabel, false, false));
            }
            return;
        }

        if ("remove-field".equals(action)) {
            String apiName = stringValue(after.get("removed"));
            if (apiName != null) {
                FieldMeta meta = fieldMeta(fields, kind, apiName);
                boolean redact = meta.sensitive() && !canSensitive;
                Object oldVal = before.get(apiName);
                changes.add(new AuditChange(apiName, meta.label(), redact && oldVal != null ? REDACTED_MASK : oldVal,
                        "(Removed from record)", meta.sensitive(), redact));
                details.put("field", apiName);
            }
            return;
        }

        if ("add-field-back".equals(action)) {
            String apiName = stringValue(after.get("added"));
            if (apiName != null) {
                FieldMeta meta = fieldMeta(fields, kind, apiName);
                changes.add(new AuditChange(apiName, meta.label(), null, "(Restored to record)", meta.sensitive(), false));
                details.put("field", apiName);
            }
            return;
        }

        if ("flag-hot".equals(action) || "clear-hot".equals(action)) {
            boolean hot = Boolean.TRUE.equals(after.get("hot"));
            boolean wasHot = before.get("hotSince") != null && !"null".equals(String.valueOf(before.get("hotSince")));
            changes.add(new AuditChange("hot", "Hot flag", wasHot ? "Hot" : "Not hot", hot ? "Hot" : "Not hot", false, false));
            return;
        }

        if ("archive".equals(action) || "restore".equals(action)) {
            return;
        }

        if (after.containsKey("via")) {
            details.put("via", after.get("via"));
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (NON_FIELD_DETAIL_KEYS.contains(key)) {
                continue;
            }
            Object bVal = before.get(key);
            Object aVal = after.get(key);
            if ("create".equals(action) && aVal == null) {
                continue;
            }
            if (!"create".equals(action) && Objects.equals(bVal, aVal)) {
                continue;
            }
            FieldMeta meta = fieldMeta(fields, kind, key);
            boolean redact = meta.sensitive() && !canSensitive;
            changes.add(new AuditChange(
                    key,
                    meta.label(),
                    redact && bVal != null ? REDACTED_MASK : bVal,
                    redact && aVal != null ? REDACTED_MASK : aVal,
                    meta.sensitive(),
                    redact));
        }
    }

    private void buildLinkChanges(String action, Map<String, Object> before, Map<String, Object> after,
            List<AuditChange> changes, Map<String, Object> details) {
        if ("make-primary".equals(action)) {
            details.put("primary", true);
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if ("personId".equals(key) || "entityId".equals(key)) {
                continue;
            }
            Object bVal = before.get(key);
            Object aVal = after.get(key);
            if ("create".equals(action) && aVal == null) {
                continue;
            }
            if (!"create".equals(action) && Objects.equals(bVal, aVal)) {
                continue;
            }
            changes.add(new AuditChange(key, humanKeyLabel(key), bVal, aVal, false, false));
        }
    }

    private void buildCustomFieldChanges(String action, Map<String, Object> before, Map<String, Object> after,
            List<AuditChange> changes, Map<String, Object> details) {
        copyIfPresent(before, details, "object", "apiName", "dataType");
        copyIfPresent(after, details, "object", "apiName", "dataType", "section", "recordsWithValue",
                "archivedRecordsWithValue", "valuesDeleted", "picklistDeleted", "direction");

        if ("retire".equals(action) || "reactivate".equals(action)) {
            changes.add(new AuditChange("active", "Status",
                    Boolean.TRUE.equals(before.get("active")) ? "Enabled" : "Disabled",
                    Boolean.TRUE.equals(after.get("active")) ? "Enabled" : "Disabled",
                    false, false));
            return;
        }

        if ("delete".equals(action) || "delete-permanently".equals(action) || "restore".equals(action)
                || "order".equals(action) || "order-list".equals(action) || "move".equals(action)) {
            return;
        }

        if ("update".equals(action)) {
            for (var e : after.entrySet()) {
                String key = e.getKey();
                if (IGNORED_METADATA_KEYS.contains(key)) {
                    continue;
                }
                Object bVal = before.get(key);
                Object aVal = e.getValue();
                if (!Objects.equals(bVal, aVal)) {
                    changes.add(new AuditChange(key, humanKeyLabel(key), bVal, aVal, false, false));
                }
            }
            return;
        }

        // create
        for (var e : after.entrySet()) {
            String key = e.getKey();
            if (IGNORED_METADATA_KEYS.contains(key) || e.getValue() == null) {
                continue;
            }
            changes.add(new AuditChange(key, humanKeyLabel(key), null, e.getValue(), false, false));
        }
    }

    private void buildGenericChanges(Map<String, Object> before, Map<String, Object> after,
            List<AuditChange> changes, Map<String, Object> details) {
        copyIfPresent(after, details, "object", "apiName", "set", "code");
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (IGNORED_METADATA_KEYS.contains(key)) {
                continue;
            }
            Object bVal = before.get(key);
            Object aVal = after.get(key);
            if (!Objects.equals(bVal, aVal)) {
                changes.add(new AuditChange(key, humanKeyLabel(key), bVal, aVal, false, false));
            }
        }
    }

    private Map<String, UserMeta> loadUsers() {
        Map<String, UserMeta> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name, initials FROM app_user").query(rs -> {
            out.put(rs.getString("id"), new UserMeta(rs.getString("name"), rs.getString("initials")));
        });
        return out;
    }

    private Map<String, FieldMeta> loadFieldMeta() {
        Map<String, FieldMeta> out = new LinkedHashMap<>();
        // First seed from permanently-deleted field snapshots so historical values stay masked & labelled.
        jdbc.sql("""
                SELECT changes->'before'->>'object' AS obj,
                       changes->'before'->>'apiName' AS api,
                       changes->'before'->>'label' AS label,
                       COALESCE((changes->'before'->>'sensitive')::boolean, false) AS sensitive
                FROM audit_event
                WHERE record_kind = 'custom_field_def' AND action = 'delete-permanently'
                ORDER BY id ASC""")
                .query(rs -> {
                    String obj = rs.getString("obj");
                    String api = rs.getString("api");
                    String label = rs.getString("label");
                    if (obj != null && api != null) {
                        out.put(obj + "." + api, new FieldMeta(label != null ? label : api, rs.getBoolean("sensitive")));
                    }
                });
        // Live and soft-deleted field definitions overwrite with latest label / sensitivity.
        jdbc.sql("SELECT object_api_name, api_name, label, sensitive FROM custom_field_def").query(rs -> {
            out.put(rs.getString("object_api_name") + "." + rs.getString("api_name"),
                    new FieldMeta(rs.getString("label"), rs.getBoolean("sensitive")));
        });
        return out;
    }

    private Map<UUID, String> loadFieldLabelsById() {
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, label FROM custom_field_def").query(rs -> {
            out.put(rs.getObject("id", UUID.class), rs.getString("label"));
        });
        return out;
    }

    private Set<UUID> collectRecordIds(List<ParsedRow> parsed, String directKind, String linkJsonKey) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (ParsedRow p : parsed) {
            if (directKind.equals(p.raw().recordKind()) && p.raw().recordId() != null) {
                ids.add(p.raw().recordId());
            } else if ("entity_link".equals(p.raw().recordKind())) {
                UUID fromChange = parseUuid(firstNonNull(p.after().get(linkJsonKey), p.before().get(linkJsonKey)));
                if (fromChange != null) {
                    ids.add(fromChange);
                }
            }
        }
        return ids;
    }

    private Map<UUID, String> loadRecordNames(String object, Collection<UUID> ids) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String expr = "person".equals(object)
                ? "NULLIF(trim(concat_ws(' ', custom_fields->>'firstName', custom_fields->>'lastName')), '')"
                : "NULLIF(trim(custom_fields->>'name'), '')";
        jdbc.sql("SELECT id, %s AS title FROM %s WHERE id IN (:ids)".formatted(expr, object))
                .param("ids", ids)
                .query(rs -> {
                    String title = rs.getString("title");
                    if (title != null) {
                        out.put(rs.getObject("id", UUID.class), title);
                    }
                });
        return out;
    }

    private FieldMeta fieldMeta(Map<String, FieldMeta> fields, String object, String apiName) {
        FieldMeta meta = fields.get(object + "." + apiName);
        return meta != null ? meta : new FieldMeta(humanKeyLabel(apiName), false);
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String firstNonNullString(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String stringValue(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static UUID parseUuid(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String initials(String id) {
        if (id == null || id.isBlank()) {
            return "?";
        }
        return id.substring(0, Math.min(2, id.length())).toUpperCase();
    }

    private static String humanKeyLabel(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return switch (key) {
            case "jobTitle" -> "Job title";
            case "department" -> "Department";
            case "role" -> "Role";
            case "email" -> "Email";
            case "primary" -> "Primary link";
            case "showInList" -> "Show in list";
            case "showOnCreate" -> "Show on new form";
            case "showOnDetail" -> "Show on record page";
            case "maxLength" -> "Max length";
            case "decimalPlaces" -> "Decimal places";
            case "defaultValue" -> "Default value";
            case "displayRules" -> "Display rules";
            default -> key;
        };
    }
}
