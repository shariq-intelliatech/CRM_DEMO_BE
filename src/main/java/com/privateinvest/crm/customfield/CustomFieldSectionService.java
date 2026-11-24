package com.privateinvest.crm.customfield;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.ConflictException;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionCreate;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionDef;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionUpdate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages persisted custom field sections: create, rename, reorder, and delete with safe field reassignment. */
@Service
@RequiredArgsConstructor
public class CustomFieldSectionService {

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final PermissionService permissions;
    private final CustomFieldService customFields;

    @Transactional(readOnly = true)
    public List<SectionDef> list(String object) {
        String key = CustomFieldService.recordKey(object);
        return jdbc.sql("""
                        SELECT s.id, s.object_api_name, s.name, s.description, s.sort_order, s.version,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NULL) AS field_count,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NULL AND c.active) AS active_field_count,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NOT NULL) AS deleted_field_count
                        FROM custom_field_section s
                        LEFT JOIN custom_field_def c
                          ON c.object_api_name = s.object_api_name
                         AND lower(btrim(c.section)) = lower(btrim(s.name))
                        WHERE s.object_api_name = :obj
                        GROUP BY s.id, s.object_api_name, s.name, s.description, s.sort_order, s.version
                        ORDER BY s.sort_order, s.name""")
                .param("obj", key)
                .query((rs, i) -> new SectionDef(
                        rs.getObject("id", UUID.class),
                        rs.getString("object_api_name"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("sort_order"),
                        rs.getLong("version"),
                        rs.getLong("field_count"),
                        rs.getLong("active_field_count"),
                        rs.getLong("deleted_field_count")))
                .list();
    }

    @Transactional(readOnly = true)
    public SectionDef get(UUID id) {
        return jdbc.sql("""
                        SELECT s.id, s.object_api_name, s.name, s.description, s.sort_order, s.version,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NULL) AS field_count,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NULL AND c.active) AS active_field_count,
                               COUNT(c.id) FILTER (WHERE c.deleted_at IS NOT NULL) AS deleted_field_count
                        FROM custom_field_section s
                        LEFT JOIN custom_field_def c
                          ON c.object_api_name = s.object_api_name
                         AND lower(btrim(c.section)) = lower(btrim(s.name))
                        WHERE s.id = :id
                        GROUP BY s.id, s.object_api_name, s.name, s.description, s.sort_order, s.version""")
                .param("id", id)
                .query((rs, i) -> new SectionDef(
                        rs.getObject("id", UUID.class),
                        rs.getString("object_api_name"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("sort_order"),
                        rs.getLong("version"),
                        rs.getLong("field_count"),
                        rs.getLong("active_field_count"),
                        rs.getLong("deleted_field_count")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Section", id));
    }

    @Transactional
    public SectionDef create(SectionCreate c) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "create sections");
        String object = CustomFieldService.recordKey(c.object());
        String name = c.name().trim();
        if (nameTaken(object, name, null)) {
            throw new FieldValidationException(Map.of("name", "A section with this name already exists."));
        }
        int nextOrder = jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM custom_field_section WHERE object_api_name = :obj")
                .param("obj", object)
                .query(Integer.class)
                .single();
        UUID id = jdbc.sql("""
                        INSERT INTO custom_field_section (object_api_name, name, description, sort_order, created_by, updated_by)
                        VALUES (:obj, :name, :desc, :sort, :user, :user)
                        RETURNING id""")
                .param("obj", object)
                .param("name", name)
                .param("desc", Formats.clean(c.description()))
                .param("sort", nextOrder)
                .param("user", currentUser.id())
                .query(UUID.class)
                .single();
        audit.record("custom_field_section", id, "create", null, Map.of("object", object, "name", name), null);
        customFields.evict();
        return get(id);
    }

    @Transactional
    public SectionDef update(UUID id, SectionUpdate u) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "edit sections");
        SectionDef current = get(id);
        String nextName = u.name().trim();
        if (nameTaken(current.object(), nextName, id)) {
            throw new FieldValidationException(Map.of("name", "A section with this name already exists."));
        }
        int updated = jdbc.sql("""
                        UPDATE custom_field_section
                        SET name = :name, description = :desc, updated_at = now(), updated_by = :user, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("name", nextName)
                .param("desc", Formats.clean(u.description()))
                .param("user", currentUser.id())
                .param("id", id)
                .param("version", u.version())
                .update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Section " + id + " changed");
        }
        if (!current.name().equals(nextName)) {
            jdbc.sql("""
                            UPDATE custom_field_def
                            SET section = :nextName, updated_at = now(), updated_by = :user, version = version + 1
                            WHERE object_api_name = :obj AND lower(btrim(section)) = lower(btrim(:oldName))""")
                    .param("nextName", nextName)
                    .param("user", currentUser.id())
                    .param("obj", current.object())
                    .param("oldName", current.name())
                    .update();
        }
        audit.record("custom_field_section", id, "update",
                Map.of("name", current.name(), "description", current.description() == null ? "" : current.description()),
                Map.of("name", nextName, "description", Formats.clean(u.description()) == null ? "" : Formats.clean(u.description())),
                null);
        customFields.evict();
        return get(id);
    }

    @Transactional
    public List<SectionDef> move(UUID id, int direction) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "reorder sections");
        SectionDef target = get(id);
        List<SectionDef> sections = new ArrayList<>(list(target.object()));
        int i = -1;
        for (int idx = 0; idx < sections.size(); idx++) {
            if (sections.get(idx).id().equals(id)) {
                i = idx;
                break;
            }
        }
        int j = i + Integer.signum(direction);
        if (direction != 0 && i >= 0 && j >= 0 && j < sections.size()) {
            sections.set(i, sections.get(j));
            sections.set(j, target);
            writeOrder(sections.stream().map(SectionDef::id).toList());
            customFields.syncFieldOrderBySections(target.object());
            audit.record("custom_field_section", id, "move", null, Map.of("direction", direction), null);
            customFields.evict();
        }
        return list(target.object());
    }

    @Transactional
    public List<SectionDef> order(String object, List<UUID> sectionIds) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "reorder sections");
        String key = CustomFieldService.recordKey(object);
        List<SectionDef> sections = list(key);
        Set<UUID> known = new LinkedHashSet<>(sections.stream().map(SectionDef::id).toList());
        Set<UUID> requested = new LinkedHashSet<>(sectionIds);
        if (requested.size() != sectionIds.size() || !known.containsAll(requested)) {
            throw new FieldValidationException(Map.of("sectionIds", "List each section of this record type once."));
        }
        List<UUID> ordered = new ArrayList<>(requested);
        sections.stream().map(SectionDef::id).filter(id -> !requested.contains(id)).forEach(ordered::add);
        writeOrder(ordered);
        customFields.syncFieldOrderBySections(key);
        audit.record("custom_field_section", null, "order", null, Map.of("object", key, "sectionIds", ordered), null);
        customFields.evict();
        return list(key);
    }

    @Transactional
    public Map<String, Object> delete(UUID id, long version, UUID moveToSectionId) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "delete sections");
        Long locked = jdbc.sql("SELECT version FROM custom_field_section WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Section", id));
        if (locked != version) {
            throw new OptimisticLockingFailureException("Section " + id + " changed");
        }
        SectionDef target = get(id);
        List<SectionDef> all = list(target.object());
        if (all.size() <= 1) {
            throw new ConflictException("last-section", "At least one section is required.", Map.of());
        }
        long totalFields = target.fieldCount() + target.deletedFieldCount();
        String movedToName = null;
        if (totalFields > 0) {
            if (moveToSectionId == null || moveToSectionId.equals(id)) {
                throw new ConflictException("section-not-empty",
                        "Choose another section to move this section's fields to before deleting it.", Map.of());
            }
            SectionDef destination = all.stream()
                    .filter(s -> s.id().equals(moveToSectionId))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Target section", moveToSectionId));
            movedToName = destination.name();
            // Place moved fields after the destination's existing fields while keeping their relative order.
            int baseOrder = jdbc.sql("""
                            SELECT COALESCE(MAX(sort_order), -1) + 1
                            FROM custom_field_def
                            WHERE object_api_name = :obj AND lower(btrim(section)) = lower(btrim(:sec))""")
                    .param("obj", target.object())
                    .param("sec", destination.name())
                    .query(Integer.class)
                    .single();
            List<UUID> movingIds = jdbc.sql("""
                            SELECT id FROM custom_field_def
                            WHERE object_api_name = :obj AND lower(btrim(section)) = lower(btrim(:sec))
                            ORDER BY sort_order, label""")
                    .param("obj", target.object())
                    .param("sec", target.name())
                    .query(UUID.class)
                    .list();
            for (int k = 0; k < movingIds.size(); k++) {
                jdbc.sql("""
                                UPDATE custom_field_def
                                SET section = :dest, sort_order = :sort, updated_at = now(), updated_by = :user, version = version + 1
                                WHERE id = :id""")
                        .param("dest", destination.name())
                        .param("sort", baseOrder + k)
                        .param("user", currentUser.id())
                        .param("id", movingIds.get(k))
                        .update();
            }
        }
        jdbc.sql("DELETE FROM custom_field_section WHERE id = :id").param("id", id).update();
        // Compact section sort orders 0..N-1.
        List<UUID> remaining = list(target.object()).stream().map(SectionDef::id).toList();
        writeOrder(remaining);
        customFields.syncFieldOrderBySections(target.object());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", target.name());
        result.put("fieldsMoved", totalFields);
        if (movedToName != null) {
            result.put("movedTo", movedToName);
        }
        audit.record("custom_field_section", id, "delete", Map.of("name", target.name()), result, null);
        customFields.evict();
        return result;
    }

    private boolean nameTaken(String object, String name, UUID exceptId) {
        return jdbc.sql("""
                        SELECT count(*) FROM custom_field_section
                        WHERE object_api_name = :obj
                          AND lower(btrim(name)) = lower(btrim(:name))
                          AND (CAST(:except AS uuid) IS NULL OR id <> CAST(:except AS uuid))""")
                .param("obj", object)
                .param("name", name)
                .param("except", exceptId)
                .query(Long.class)
                .single() > 0;
    }

    private void writeOrder(List<UUID> ids) {
        for (int k = 0; k < ids.size(); k++) {
            jdbc.sql("UPDATE custom_field_section SET sort_order = :sort WHERE id = :id")
                    .param("sort", k)
                    .param("id", ids.get(k))
                    .update();
        }
    }
}
