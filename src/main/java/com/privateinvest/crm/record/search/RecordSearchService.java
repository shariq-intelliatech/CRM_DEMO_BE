package com.privateinvest.crm.record.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldService;
import com.privateinvest.crm.record.FieldLogic;
import com.privateinvest.crm.record.ObjectRules;
import com.privateinvest.crm.record.ObjectRules.Rules;
import com.privateinvest.crm.record.RecordFieldWriter;
import com.privateinvest.crm.record.RecordObjects;
import com.privateinvest.crm.record.RecordObjects.ObjectType;
import com.privateinvest.crm.record.search.SearchDtos.FilterNode;
import com.privateinvest.crm.record.search.SearchDtos.LookupItem;
import com.privateinvest.crm.record.search.SearchDtos.LookupPage;
import com.privateinvest.crm.record.search.SearchDtos.SearchItem;
import com.privateinvest.crm.record.search.SearchDtos.SearchPage;
import com.privateinvest.crm.record.search.SearchDtos.SearchRequest;
import com.privateinvest.crm.record.search.SearchDtos.SortKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Search (filter, sort, page) and pickers for every record type, driven by its field registry and rules (record
 * name, owner, default order). Filtering, sorting and paging run in the database; a page costs two queries
 * (rows and count), never one per row.
 */
@Service
@RequiredArgsConstructor
public class RecordSearchService {

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;
    private static final int MAX_LOOKUP = 50;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CustomFieldService customFields;
    private final PermissionService permissions;
    private final CurrentUser currentUser;
    private final ObjectRules objectRules;
    private final RecordFieldWriter writer;

    @Transactional(readOnly = true)
    public SearchPage search(String object, SearchRequest r) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.readPermission(), "see " + type.plural());
        boolean archived = "ARCHIVED".equalsIgnoreCase(r.scope());
        if (archived) {
            permissions.require(type.archivePermission(), "see archived " + type.plural());
        } else if (r.scope() != null && !"ACTIVE".equalsIgnoreCase(r.scope())) {
            throw RecordQueryBuilder.invalid("scope", "Scope is ACTIVE or ARCHIVED.");
        }
        int size = r.size() == null ? DEFAULT_SIZE : r.size();
        int page = r.page() == null ? 0 : r.page();
        if (size < 1 || size > MAX_SIZE) {
            throw RecordQueryBuilder.invalid("size", "Page size is 1 to " + MAX_SIZE + ".");
        }
        if (page < 0) {
            throw RecordQueryBuilder.invalid("page", "Pages start at 0.");
        }

        Rules rules = objectRules.effective(type.apiName());
        List<CustomFieldDef> registry = customFields.list(type.apiName());
        boolean canSensitive = permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE);
        RecordQueryBuilder qb = builder(type, registry, canSensitive, rules);
        List<String> where = new ArrayList<>();
        where.add(archived ? "p.archived_at IS NOT NULL" : "p.archived_at IS NULL");
        addIfPresent(where, qb.quickSearch(r.q()));
        addIfPresent(where, ownerScope(type, qb, rules, r.ownerScope()));
        addIfPresent(where, flag(r.flag()));
        addIfPresent(where, qb.filter(r.filter()));
        addIfPresent(where, qb.filter(FilterLogic.parse(r.conditions(), r.logic())));
        String whereSql = String.join(" AND ", where);
        String orderBy = qb.orderBy(r.sort(), rules.defaultSort());
        List<CustomFieldDef> columns = columns(registry, r.columns(), qb, canSensitive);

        long total = jdbc.sql("SELECT count(*) FROM " + type.table() + " p WHERE " + whereSql)
                .params(qb.params()).query(Long.class).single();
        Map<String, Object> pageParams = new HashMap<>(qb.params());
        pageParams.put("limit", size);
        pageParams.put("offset", (long) page * size);
        java.time.LocalDate today = writer.today();
        List<SearchItem> items = jdbc.sql("SELECT p.id, p.version, p.archived_at, p.custom_fields::text AS custom_fields "
                        + "FROM " + type.table() + " p WHERE " + whereSql + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(pageParams)
                .query((rs, i) -> {
                    Map<String, Object> stored = customFields.decode(type.apiName(), rs.getString("custom_fields"));
                    FieldLogic.derive(registry, stored, today);
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (CustomFieldDef d : columns) {
                        Object v = stored.get(d.apiName());
                        if (!CustomFieldService.isEmpty(v)) {
                            values.put(d.apiName(), v);
                        }
                    }
                    return new SearchItem(rs.getObject("id", UUID.class), rs.getLong("version"), title(rules, stored),
                            rs.getObject("archived_at", OffsetDateTime.class) != null, values);
                })
                .list();
        int totalPages = (int) ((total + size - 1) / size);
        return new SearchPage(items, columns.stream().map(CustomFieldDef::apiName).toList(), page, size, total, totalPages);
    }

    /**
     * The picker: live records matching {@code q} by the same quick search, a page at a time, without a count
     * ({@code size + 1} rows tell whether there is more). {@code ids} resolves labels of chosen records;
     * {@code field} and {@code values} narrow the choice (e.g. only Entities whose type is Aggregator). Title and subtitle come
     * from the record-name and subtitle rules.
     */
    @Transactional(readOnly = true)
    public LookupPage lookup(String object, String q, int page, int size, List<UUID> exclude, List<UUID> ids,
                             String field, List<String> values) {
        ObjectType type = RecordObjects.get(object);
        permissions.require(type.readPermission(), "see " + type.plural());
        if (size < 1 || size > MAX_LOOKUP) {
            throw RecordQueryBuilder.invalid("size", "Page size is 1 to " + MAX_LOOKUP + ".");
        }
        Rules rules = objectRules.effective(type.apiName());
        List<CustomFieldDef> registry = customFields.list(type.apiName());
        RecordQueryBuilder qb = builder(type, registry, permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE), rules);
        List<String> where = new ArrayList<>();
        where.add("p.archived_at IS NULL");
        boolean byIds = ids != null && !ids.isEmpty();
        if (byIds) {
            where.add("p.id IN (" + qb.param(ids) + ")");
        } else {
            addIfPresent(where, qb.quickSearch(q));
            if (field != null && !field.isBlank()) {
                CustomFieldDef d = qb.field(field, "filter");
                String op = d.dataType() == DataType.MULTI_PICKLIST ? "CONTAINS_ANY" : "IN";
                addIfPresent(where, qb.filter(new FilterNode(null, null, field, op, values)));
            }
            if (exclude != null && !exclude.isEmpty()) {
                where.add("p.id NOT IN (" + qb.param(exclude) + ")");
            }
        }
        List<SortKey> lookupSort = rules.defaultSort().isEmpty()
                ? rules.nameFields().stream().limit(RecordQueryBuilder.MAX_SORT).map(f -> new SortKey(f, "ASC")).toList()
                : rules.defaultSort();
        String orderBy = qb.orderBy(null, lookupSort);
        Map<String, Object> params = new HashMap<>(qb.params());
        params.put("limit", byIds ? ids.size() : size + 1);
        params.put("offset", byIds ? 0 : (long) page * size);
        Map<String, CustomFieldDef> byApi = registry.stream().collect(Collectors.toMap(CustomFieldDef::apiName, d -> d));
        Map<String, Map<String, String>> labels = optionLabels(rules.subtitleFields().stream().map(byApi::get).toList());
        Map<String, String> userNames = new HashMap<>();
        jdbc.sql("SELECT id, name FROM app_user").query(rs -> {
            userNames.put(rs.getString(1), rs.getString(2));
        });
        List<LookupItem> items = new ArrayList<>(jdbc.sql("SELECT p.id, p.custom_fields::text AS custom_fields FROM "
                        + type.table() + " p WHERE " + String.join(" AND ", where) + " ORDER BY " + orderBy
                        + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, i) -> {
                    Map<String, Object> stored = customFields.decode(type.apiName(), rs.getString("custom_fields"));
                    String subtitle = rules.subtitleFields().stream()
                            .map(f -> display(byApi.get(f), stored.get(f), labels, userNames))
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.joining(" · "));
                    return new LookupItem(rs.getObject("id", UUID.class), title(rules, stored), subtitle.isEmpty() ? null : subtitle);
                })
                .list());
        boolean hasMore = !byIds && items.size() > size;
        if (hasMore) {
            items.removeLast();
        }
        return new LookupPage(items, page, size, hasMore);
    }

    /**
     * Checks a saved list view's definition without running it: every field, operator and value, the filter
     * logic, the columns and the sort must be valid for the current registry. Throws a validation error otherwise.
     */
    public void check(String object, String ownerScope, List<FilterNode> conditions, String logic, List<String> columns,
                      List<SortKey> sort) {
        ObjectType type = RecordObjects.get(object);
        Rules rules = objectRules.effective(type.apiName());
        List<CustomFieldDef> registry = customFields.list(type.apiName());
        boolean canSensitive = permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE);
        RecordQueryBuilder qb = builder(type, registry, canSensitive, rules);
        ownerScope(type, qb, rules, ownerScope);
        qb.filter(FilterLogic.parse(conditions, logic));
        qb.orderBy(sort, rules.defaultSort());
        columns(registry, columns, qb, canSensitive);
    }

    /* ---------- Helpers ---------- */

    private RecordQueryBuilder builder(ObjectType type, List<CustomFieldDef> registry, boolean canSensitive, Rules rules) {
        return new RecordQueryBuilder(registry, canSensitive, currentUser.id(), json, writer.today(), rules.nameFields(),
                type.singular());
    }

    /** "Show me" in Salesforce terms: ALL records (default) or MINE, those whose owner field is the current user. */
    private static String ownerScope(ObjectType type, RecordQueryBuilder qb, Rules rules, String scope) {
        if (scope == null || scope.isBlank() || scope.equalsIgnoreCase("ALL")) {
            return null;
        }
        if (!scope.equalsIgnoreCase("MINE")) {
            throw RecordQueryBuilder.invalid("ownerScope", "Show all " + type.plural() + " (ALL) or only yours (MINE).");
        }
        if (rules.ownerField() == null) {
            throw RecordQueryBuilder.invalid("ownerScope", "\"My " + type.plural().toLowerCase() + "\" needs an owner field. "
                    + "Choose one in Setup → " + type.singular() + " rules.");
        }
        return qb.filter(new FilterNode(null, null, rules.ownerField(), "IS_ME", null));
    }

    /** Flags are system columns, not fields: a fixed condition each. */
    private static String flag(String flag) {
        if (flag == null || flag.isBlank()) {
            return null;
        }
        return switch (flag.trim().toUpperCase()) {
            case "POSSIBLE_DUPLICATE" -> "p.possible_duplicate_reason IS NOT NULL";
            case "HOT" -> "p.hot_since IS NOT NULL";
            default -> throw RecordQueryBuilder.invalid("flag", "Flag is POSSIBLE_DUPLICATE or HOT.");
        };
    }

    private static String title(Rules rules, Map<String, Object> values) {
        String t = rules.nameFields().stream().map(values::get).filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf).collect(Collectors.joining(" "));
        return t.isBlank() ? "(No name)" : t;
    }

    /** Option labels of the given dropdown fields, by set code then option code. */
    private Map<String, Map<String, String>> optionLabels(List<CustomFieldDef> defs) {
        List<String> sets = defs.stream().filter(d -> d != null && d.picklist() != null).map(CustomFieldDef::picklist).distinct().toList();
        Map<String, Map<String, String>> out = new HashMap<>();
        if (sets.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT set_code, code, label FROM picklist_value WHERE set_code IN (:sets) ORDER BY sort_order")
                .param("sets", sets)
                .query(rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
                });
        return out;
    }

    /** A value as the picker shows it: option labels for dropdowns, the user's name, a list's primary item. */
    private static String display(CustomFieldDef d, Object v, Map<String, Map<String, String>> labels, Map<String, String> users) {
        if (d == null || v == null) {
            return null;
        }
        if (d.dataType().isList()) {
            return CustomFieldService.primaryValue(v);
        }
        if (d.dataType() == DataType.USER) {
            return users.getOrDefault(String.valueOf(v), String.valueOf(v));
        }
        if (d.dataType().isPicklist()) {
            Map<String, String> set = labels.getOrDefault(d.picklist(), Map.of());
            List<?> codes = v instanceof List<?> l ? l : List.of(v);
            return codes.stream().map(c -> set.getOrDefault(String.valueOf(c), String.valueOf(c))).collect(Collectors.joining(", "));
        }
        if (v instanceof Boolean b) {
            return b ? d.label() : null;
        }
        return v instanceof BigDecimal n ? n.toPlainString() : String.valueOf(v);
    }

    /** Requested columns, or the fields shown in the list in list order. Unknown or hidden names are refused. */
    private static List<CustomFieldDef> columns(List<CustomFieldDef> registry, List<String> requested,
                                                RecordQueryBuilder qb, boolean canSensitive) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().distinct().map(api -> qb.field(api, "columns")).toList();
        }
        return registry.stream()
                .filter(d -> d.active() && d.showInList() && (!d.sensitive() || canSensitive))
                .sorted(Comparator.comparingInt(CustomFieldDef::listOrder))
                .toList();
    }

    private static void addIfPresent(List<String> where, String sql) {
        if (sql != null) {
            where.add(sql);
        }
    }
}
