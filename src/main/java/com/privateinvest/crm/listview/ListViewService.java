package com.privateinvest.crm.listview;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.listview.ListViewDtos.ListView;
import com.privateinvest.crm.listview.ListViewDtos.ListViewInput;
import com.privateinvest.crm.record.search.SearchDtos.FilterNode;
import com.privateinvest.crm.record.search.SearchDtos.SortKey;
import com.privateinvest.crm.record.search.RecordSearchService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved list views. Anyone who can read the records keeps private views; shared views need LIST_VIEW_SHARE.
 * A view is checked against the field registry when it is saved; when a field later disappears, the list
 * screen drops the condition and says so.
 */
@Service
@RequiredArgsConstructor
public class ListViewService {

    /** Record types with list views (a fixed whitelist). */
    private static final Set<String> OBJECTS = Set.of("person", "entity");
    private static final int MAX_VIEWS_PER_USER = 50;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CurrentUser currentUser;
    private final PermissionService permissions;
    private final RecordSearchService search;
    private final AuditService audit;

    /** The current user's views and every shared view, by name; the pinned one is marked. */
    @Transactional(readOnly = true)
    public List<ListView> list(String object) {
        check(object);
        return jdbc.sql(select("v.object_api_name = :obj AND (v.owner_id = :me OR v.shared)") + " ORDER BY lower(v.name), v.id")
                .param("obj", object).param("me", currentUser.id())
                .query((rs, i) -> row(rs)).list();
    }

    @Transactional(readOnly = true)
    public ListView get(UUID id) {
        return jdbc.sql(select("v.id = :id AND (v.owner_id = :me OR v.shared)"))
                .param("id", id).param("me", currentUser.id())
                .query((rs, i) -> row(rs)).optional()
                .orElseThrow(() -> new NotFoundException("List view", id));
    }

    @Transactional
    public ListView create(String object, ListViewInput in) {
        check(object);
        validate(object, in);
        long mine = jdbc.sql("SELECT count(*) FROM list_view WHERE owner_id = :me AND object_api_name = :obj")
                .param("me", currentUser.id()).param("obj", object).query(Long.class).single();
        if (mine >= MAX_VIEWS_PER_USER) {
            throw new FieldValidationException(Map.of("name", "You already have " + MAX_VIEWS_PER_USER + " list views. Delete one first."));
        }
        UUID id;
        try {
            id = jdbc.sql("""
                            INSERT INTO list_view (object_api_name, name, owner_id, shared, owner_scope, conditions, logic,
                                columns, sort, created_by, updated_by)
                            VALUES (:obj, :name, :me, :shared, :scope, CAST(:conditions AS jsonb), :logic,
                                CAST(:columns AS jsonb), CAST(:sort AS jsonb), :me, :me)
                            RETURNING id""")
                    .param("obj", object)
                    .params(values(in))
                    .param("me", currentUser.id())
                    .query(UUID.class).single();
        } catch (DuplicateKeyException ex) {
            throw nameTaken();
        }
        audit.record("list_view", id, "create", null, in, null);
        return get(id);
    }

    @Transactional
    public ListView update(UUID id, long version, ListViewInput in) {
        ListView current = get(id);
        requireEdit(current);
        validate(current.object(), in);
        int n;
        try {
            n = jdbc.sql("""
                            UPDATE list_view SET name = :name, shared = :shared, owner_scope = :scope,
                                conditions = CAST(:conditions AS jsonb), logic = :logic, columns = CAST(:columns AS jsonb),
                                sort = CAST(:sort AS jsonb), updated_at = now(), updated_by = :me, version = version + 1
                            WHERE id = :id AND version = :version""")
                    .params(values(in))
                    .param("me", currentUser.id()).param("id", id).param("version", version)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw nameTaken();
        }
        if (n == 0) {
            throw new OptimisticLockingFailureException("List view " + id + " changed");
        }
        audit.record("list_view", id, "update", current, in, null);
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        ListView current = get(id);
        requireEdit(current);
        jdbc.sql("DELETE FROM list_view WHERE id = :id").param("id", id).update();
        audit.record("list_view", id, "delete", current, null, null);
    }

    /** Makes the view the one this user's list opens with (replacing any earlier pin). */
    @Transactional
    public ListView pin(UUID id) {
        ListView view = get(id);
        jdbc.sql("""
                        INSERT INTO list_view_pin (user_id, object_api_name, view_id) VALUES (:me, :obj, :id)
                        ON CONFLICT (user_id, object_api_name) DO UPDATE SET view_id = EXCLUDED.view_id""")
                .param("me", currentUser.id()).param("obj", view.object()).param("id", id).update();
        return get(id);
    }

    @Transactional
    public void unpin(String object) {
        check(object);
        jdbc.sql("DELETE FROM list_view_pin WHERE user_id = :me AND object_api_name = :obj")
                .param("me", currentUser.id()).param("obj", object).update();
    }

    /* ---------- Helpers ---------- */

    private void check(String object) {
        if (!OBJECTS.contains(object)) {
            throw new NotFoundException("Record type", object);
        }
        permissions.require(com.privateinvest.crm.record.RecordObjects.get(object).readPermission(),
                "see " + com.privateinvest.crm.record.RecordObjects.get(object).plural());
    }

    /** The definition must run against today's fields; sharing needs LIST_VIEW_SHARE. */
    private void validate(String object, ListViewInput in) {
        if (in.shared() && !permissions.has(PermissionService.LIST_VIEW_SHARE)) {
            throw new ForbiddenException("You don't have permission to share list views. Save it as a private view.");
        }
        if (in.columns() != null && in.columns().size() > 30) {
            throw new FieldValidationException(Map.of("columns", "Show at most 30 columns."));
        }
        search.check(object, in.ownerScope(), in.conditions(), in.logic(), in.columns(), in.sort());
    }

    /** Owners change their views; shared views can also be changed by users who may share. */
    private void requireEdit(ListView v) {
        if (!v.canEdit()) {
            throw new ForbiddenException("Only the owner, or someone who manages shared list views, can change '" + v.name() + "'.");
        }
    }

    private Map<String, Object> values(ListViewInput in) {
        String logic = in.logic() == null || in.logic().isBlank() ? null : in.logic().trim().toUpperCase();
        return Map.of(
                "name", in.name().trim(),
                "shared", in.shared(),
                "scope", in.ownerScope() == null || in.ownerScope().isBlank() ? "ALL" : in.ownerScope().trim().toUpperCase(),
                "conditions", json.writeValueAsString(in.conditions() == null ? List.of() : in.conditions()),
                "logic", logic == null ? "" : logic,
                "columns", json.writeValueAsString(in.columns() == null ? List.of() : in.columns()),
                "sort", json.writeValueAsString(in.sort() == null ? List.of() : in.sort()));
    }

    private static FieldValidationException nameTaken() {
        return new FieldValidationException(Map.of("name", "A list view with this name already exists."));
    }

    private static String select(String where) {
        return """
                SELECT v.id, v.object_api_name, v.name, v.owner_id, u.name AS owner_name, v.shared, v.owner_scope,
                       v.conditions::text AS conditions, v.logic, v.columns::text AS columns, v.sort::text AS sort,
                       v.updated_at, v.version,
                       EXISTS (SELECT 1 FROM list_view_pin pin WHERE pin.view_id = v.id AND pin.user_id = :me) AS pinned
                FROM list_view v JOIN app_user u ON u.id = v.owner_id
                WHERE """ + " " + where;
    }

    private ListView row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String owner = rs.getString("owner_id");
        boolean shared = rs.getBoolean("shared");
        boolean canEdit = owner.equals(currentUser.id()) || (shared && permissions.has(PermissionService.LIST_VIEW_SHARE));
        String logic = rs.getString("logic");
        return new ListView(
                rs.getObject("id", UUID.class),
                rs.getString("object_api_name"),
                rs.getString("name"),
                owner,
                rs.getString("owner_name"),
                shared,
                rs.getString("owner_scope"),
                json.readValue(rs.getString("conditions"), new TypeReference<List<FilterNode>>() { }),
                logic == null || logic.isBlank() ? null : logic,
                json.readValue(rs.getString("columns"), new TypeReference<List<String>>() { }),
                json.readValue(rs.getString("sort"), new TypeReference<List<SortKey>>() { }),
                rs.getBoolean("pinned"),
                canEdit,
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"));
    }
}
