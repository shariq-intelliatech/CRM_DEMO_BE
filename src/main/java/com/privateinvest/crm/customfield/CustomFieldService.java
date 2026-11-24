package com.privateinvest.crm.customfield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.ConflictException;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldCreate;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldUpdate;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldDtos.DeletedField;
import com.privateinvest.crm.customfield.CustomFieldDtos.FieldSettings;
import com.privateinvest.crm.customfield.CustomFieldDtos.FieldUsage;
import com.privateinvest.crm.customfield.CustomFieldDtos.FieldView;
import com.privateinvest.crm.customfield.CustomFieldDtos.Storage;
import com.privateinvest.crm.metadata.MetadataService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The field registry and runtime custom fields.
 * <ul>
 *   <li>Definitions live in custom_field_def: every Person field (system and custom) and Entity's custom fields.
 *       Type, storage and api name never change. System fields can't be deleted; custom fields can be deleted
 *       only while no record holds a value (otherwise they are disabled).</li>
 *   <li>CUSTOM values live in the record's custom_fields JSONB column, keyed by api name. A database trigger
 *       checks every key is a CUSTOM field of that record type and every value fits its type and option list.</li>
 *   <li>Every value is converted to its field type here; mandatory fields block a save; defaults apply on create;
 *       sensitive fields need CUSTOM_FIELD_SENSITIVE; managing fields needs CUSTOM_FIELD_MANAGE.</li>
 *   <li>Definitions are cached per record type and evicted when a change commits.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CustomFieldService {

    /** Record types that support custom fields, mapped to their table (a fixed whitelist, never user input). */
    private static final Map<String, String> RECORD_TABLE = Map.of("person", "person", "entity", "entity");
    /** Live fields per record type (Salesforce allows about 500 custom fields per object). */
    private static final int MAX_FIELDS_PER_OBJECT = 500;
    private static final int DEFAULT_TEXT_LENGTH = 255;
    private static final int DEFAULT_LONG_TEXT_LENGTH = 32000;
    private static final int DEFAULT_DECIMALS = 2;
    private static final int MAX_PATTERN_LENGTH = 200;
    private static final BigDecimal MAX_NUMBER = new BigDecimal("1000000000000"); // 10^12, fits NUMERIC(19,6)
    private static final Pattern PHONE = Pattern.compile("^[0-9 +()\\-]{6,30}$");
    private static final Set<String> VALIDATION_KEYS = Set.of("minLength", "min", "max", "pattern", "patternMessage", "format");
    private static final Set<String> FORMATS = Set.of("ABN", "ACN");
    private static final Set<String> ARMS = Set.of("CREDIT", "CAPITAL", "BOTH");
    /** Operators a display condition can use (evaluated the same way on the server and in the browser). */
    public static final Set<String> CONDITION_OPERATORS = Set.of("EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "CONTAINS_ANY",
            "IS_EMPTY", "IS_NOT_EMPTY", "IS_TRUE", "IS_FALSE");
    /** Facts about a record a display condition can name besides its fields. */
    public static final Set<String> CONTEXT_FIELDS = Set.of("$linkedEntityType");
    private static final int MAX_LIST_ITEMS = 10;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MetadataService metadata;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final PermissionService permissions;
    /** {@link #json} reading decimals exactly; built on first use. */
    private volatile JsonMapper exactJson;
    /** Definitions per record type ("*" = all). */
    private final Map<String, List<CustomFieldDef>> cache = new ConcurrentHashMap<>();

    /* ====================================================================================================
     * Definitions
     * ==================================================================================================== */

    /** Every field of a record type (or of all types when {@code object} is null), in form order. */
    public List<CustomFieldDef> list(String object) {
        return cache.computeIfAbsent(object == null ? "*" : object, k -> loadDefinitions(object));
    }

    /** Fields admins created (no system key): what the original custom-field screens and values show. */
    public List<CustomFieldDef> listCustom(String object) {
        return list(object).stream().filter(d -> !d.system() && !d.deleted()).toList();
    }

    private List<CustomFieldDef> loadDefinitions(String object) {
        return jdbc.sql("""
                        SELECT id, object_api_name, api_name, label, help_text, data_type, picklist_set_code,
                               mandatory, expected, sensitive, default_value::text AS default_value, show_in_list,
                               max_length, decimal_places, sort_order, active, version, storage, system_key,
                               placeholder, width, validation::text AS validation, show_on_create, show_on_detail,
                               list_order, searchable, filterable, sortable, editable_after_create, required_locked,
                               section, active AND deleted_at IS NULL AS live, deleted_at, deleted_by,
                               display_rules::text AS display_rules, formula::text AS formula, options_from, arm
                        FROM custom_field_def
                        WHERE (CAST(:obj AS varchar) IS NULL OR object_api_name = CAST(:obj AS varchar))
                        ORDER BY object_api_name, sort_order, label""")
                .param("obj", object)
                .query((rs, i) -> new CustomFieldDef(
                        rs.getObject("id", UUID.class),
                        rs.getString("object_api_name"),
                        rs.getString("api_name"),
                        rs.getString("label"),
                        rs.getString("help_text"),
                        DataType.valueOf(rs.getString("data_type")),
                        rs.getString("picklist_set_code"),
                        rs.getBoolean("mandatory"),
                        rs.getBoolean("expected"),
                        rs.getBoolean("sensitive"),
                        parseJson(rs.getString("default_value")),
                        rs.getBoolean("show_in_list"),
                        (Integer) rs.getObject("max_length"),
                        (Integer) rs.getObject("decimal_places"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("live"),
                        rs.getLong("version"),
                        Storage.valueOf(rs.getString("storage")),
                        rs.getString("system_key"),
                        rs.getString("placeholder"),
                        rs.getString("width"),
                        asMap(parseJson(rs.getString("validation"))),
                        rs.getBoolean("show_on_create"),
                        rs.getBoolean("show_on_detail"),
                        rs.getInt("list_order"),
                        rs.getBoolean("searchable"),
                        rs.getBoolean("filterable"),
                        rs.getBoolean("sortable"),
                        rs.getBoolean("editable_after_create"),
                        rs.getBoolean("required_locked"),
                        rs.getString("section") != null ? rs.getString("section") : "Personal Information",
                        rs.getObject("deleted_at", java.time.OffsetDateTime.class),
                        rs.getString("deleted_by"),
                        asMap(parseJson(rs.getString("display_rules"))),
                        rs.getString("formula") == null ? null : asMap(parseJson(rs.getString("formula"))),
                        rs.getString("options_from"),
                        rs.getString("arm")))
                .list();
    }

    public CustomFieldDef get(UUID id) {
        return list(null).stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Custom field", id));
    }

    /** Fields as forms, lists and filters see them. Disabled fields only for field managers. */
    public List<FieldView> views(String object, boolean includeDisabled) {
        recordTable(object);
        if (includeDisabled) {
            permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "see disabled fields");
        }
        boolean canSensitive = permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE);
        return list(object).stream()
                .filter(d -> !d.deleted() && (includeDisabled || d.active()))
                .map(d -> new FieldView(d, d.system(), d.deletable(), !d.sensitive() || canSensitive,
                        OperatorCatalog.forType(d.dataType()).stream().map(Enum::name).toList()))
                .toList();
    }

    @Transactional
    public CustomFieldDef create(CustomFieldCreate c) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "add custom fields");
        String object = c.object().trim().toLowerCase();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!RECORD_TABLE.containsKey(object)) {
            errors.put("object", "Custom fields can be added to Person or Entity.");
        }
        if (c.dataType() == DataType.USER) {
            errors.put("dataType", "A user field can't be added; the Owner field already exists.");
        }
        checkLimits(errors, c.dataType(), c.maxLength(), c.decimalPlaces());
        Map<String, Object> validation = checkSettings(errors, c.dataType(), c.settings());
        Map<String, Object> displayRules = checkDisplayRules(errors, object, c.settings().displayRules());
        String optionsFrom = checkOptionsFrom(errors, object, c.dataType(), c.settings().optionsFrom());
        String arm = checkArm(errors, c.settings().arm());
        String section = RECORD_TABLE.containsKey(object)
                ? resolveSection(errors, object, c.settings().section(), true, null)
                : null;
        List<String> values = c.picklistValues() == null ? List.of()
                : c.picklistValues().stream().map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
        if (c.dataType().hasOptions() && values.isEmpty()) {
            errors.put("picklistValues", c.dataType().isList() ? "Add at least one label, e.g. Mobile."
                    : "Add at least one option for the dropdown.");
        }
        throwIfAny(errors);
        long count = jdbc.sql("SELECT count(*) FROM custom_field_def WHERE object_api_name = :obj AND deleted_at IS NULL")
                .param("obj", object).query(Long.class).single();
        if (count >= MAX_FIELDS_PER_OBJECT) {
            throw new FieldValidationException(Map.of("label",
                    "This record type already has " + MAX_FIELDS_PER_OBJECT + " fields. Delete one instead of adding more."));
        }
        if (labelTaken(object, c.label(), null)) {
            throw new FieldValidationException(Map.of("label", "A field with this label already exists."));
        }

        String apiName = uniqueApiName(object, c.label());
        String setCode = c.dataType().hasOptions()
                ? createPicklist(object, apiName, c.label().trim(), c.dataType() == DataType.MULTI_PICKLIST, values)
                : null;
        // The default is checked against the new field's own rules (type, length, decimals, options, validation).
        Object defaultValue = resolveDefault(object, c.dataType(), setCode, c.maxLength(), c.decimalPlaces(),
                validation, c.defaultValue());

        int nextOrder = jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM custom_field_def WHERE object_api_name = :obj")
                .param("obj", object).query(Integer.class).single();
        int nextListOrder = jdbc.sql("SELECT COALESCE(MAX(list_order), -1) + 1 FROM custom_field_def WHERE object_api_name = :obj")
                .param("obj", object).query(Integer.class).single();
        FieldSettings s = c.settings();
        UUID id = jdbc.sql("""
                        INSERT INTO custom_field_def (object_api_name, api_name, label, help_text, data_type,
                            picklist_set_code, mandatory, expected, sensitive, default_value, show_in_list,
                            max_length, decimal_places, sort_order, list_order, placeholder, width, validation,
                            show_on_create, show_on_detail, searchable, filterable, sortable, editable_after_create,
                            section, display_rules, options_from, arm, created_by, updated_by)
                        VALUES (:obj, :api, :label, :help, :type, :set, :mandatory, :expected, :sensitive,
                            CAST(:dflt AS jsonb), :inList, :maxLength, :decimals, :sort, :listSort, :placeholder,
                            :width, CAST(:validation AS jsonb), :onCreate, :onDetail, :searchable, :filterable,
                            :sortable, :editable, :section, CAST(:rules AS jsonb), :optionsFrom, :arm, :user, :user)
                        RETURNING id""")
                .param("obj", object)
                .param("api", apiName)
                .param("label", c.label().trim())
                .param("help", Formats.clean(c.help()))
                .param("type", c.dataType().name())
                .param("set", setCode)
                .param("mandatory", c.mandatory())
                .param("expected", c.expected())
                .param("sensitive", c.sensitive())
                .param("dflt", toJson(defaultValue))
                .param("inList", c.showInList())
                .param("maxLength", c.dataType().hasLength() ? c.maxLength() : null)
                .param("decimals", c.dataType().isNumeric() ? c.decimalPlaces() : null)
                .param("sort", nextOrder)
                .param("listSort", nextListOrder)
                .param("placeholder", Formats.clean(s.placeholder()))
                .param("width", s.width() == null ? "HALF" : s.width())
                .param("validation", toJson(validation))
                .param("onCreate", s.showOnCreate() == null || s.showOnCreate())
                .param("onDetail", s.showOnDetail() == null || s.showOnDetail())
                // New custom fields are in quick search unless the admin turns it off.
                .param("searchable", (s.searchable() == null || s.searchable()) && c.dataType().isQuickSearchable())
                .param("filterable", s.filterable() == null || s.filterable())
                .param("sortable", (s.sortable() == null || s.sortable()) && c.dataType() != DataType.MULTI_PICKLIST)
                .param("editable", s.editableAfterCreate() == null || s.editableAfterCreate())
                .param("section", section)
                .param("rules", toJson(displayRules))
                .param("optionsFrom", optionsFrom)
                .param("arm", arm == null ? "BOTH" : arm)
                .param("user", currentUser.id())
                .query(UUID.class).single();
        syncFieldOrderBySections(object);
        audit.record("custom_field_def", id, "create", null, c, null);
        evict();
        return loadOne(id);
    }

    @Transactional
    public CustomFieldDef update(UUID id, CustomFieldUpdate u) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "change custom fields");
        CustomFieldDef current = get(id);
        requireLive(current);
        if (current.requiredLocked() && !u.mandatory()) {
            throw new ConflictException("field-locked", "'" + current.label() + "' must stay required.",
                    Map.of("field", "mandatory"));
        }
        FieldSettings s = u.settings();
        if (current.system() && s.editableAfterCreate() != null && s.editableAfterCreate() != current.editableAfterCreate()) {
            throw new ConflictException("field-locked", "Whether '" + current.label() + "' can change after the record "
                    + "is saved is set by the CRM's rules.", Map.of("field", "editableAfterCreate"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        checkLimits(errors, current.dataType(), u.maxLength(), u.decimalPlaces());
        Map<String, Object> checked = checkSettings(errors, current.dataType(), s);
        Map<String, Object> validation = s.validation() == null ? current.validation() : checked;
        Map<String, Object> displayRules = s.displayRules() == null ? current.displayRules()
                : checkDisplayRules(errors, current.object(), s.displayRules());
        if (displayRules != null && mentions(displayRules, current.apiName())) {
            errors.put("displayRules", "A field's conditions can't use the field itself.");
        }
        String arm = s.arm() == null ? current.arm() : checkArm(errors, s.arm());
        String section = resolveSection(errors, current.object(), s.section(), false, current.section());
        if (labelTaken(current.object(), u.label(), id)) {
            errors.put("label", "A field with this label already exists.");
        }
        throwIfAny(errors);
        // CORE and CHILD fields keep their column limits; only CUSTOM text lengths can be changed.
        Integer maxLength = current.storage() == Storage.CUSTOM && current.dataType().hasLength() ? u.maxLength() : current.maxLength();
        Object defaultValue = resolveDefault(current.object(), current.dataType(), current.picklist(),
                maxLength, u.decimalPlaces(), validation, u.defaultValue());
        int nextSortOrder = section.equals(current.section()) ? current.sortOrder()
                : jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM custom_field_def WHERE object_api_name = :obj")
                        .param("obj", current.object()).query(Integer.class).single();
        int n = jdbc.sql("""
                        UPDATE custom_field_def SET label = :label, help_text = :help, mandatory = :mandatory,
                            expected = :expected, sensitive = :sensitive, default_value = CAST(:dflt AS jsonb),
                            show_in_list = :inList, max_length = :maxLength, decimal_places = :decimals,
                            placeholder = :placeholder, width = :width, validation = CAST(:validation AS jsonb),
                            show_on_create = :onCreate, show_on_detail = :onDetail, searchable = :searchable,
                            filterable = :filterable, sortable = :sortable, editable_after_create = :editable,
                            section = :section, sort_order = :sortOrder, display_rules = CAST(:rules AS jsonb), arm = :arm,
                            updated_at = now(), updated_by = :user, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("label", u.label().trim())
                .param("help", Formats.clean(u.help()))
                .param("mandatory", u.mandatory())
                .param("expected", u.expected())
                .param("sensitive", u.sensitive())
                .param("dflt", toJson(defaultValue))
                .param("inList", u.showInList())
                .param("maxLength", maxLength)
                .param("decimals", current.dataType().isNumeric() ? u.decimalPlaces() : null)
                .param("placeholder", s.placeholder() == null ? current.placeholder() : Formats.clean(s.placeholder()))
                .param("width", s.width() == null ? current.width() : s.width())
                .param("validation", toJson(validation))
                .param("onCreate", s.showOnCreate() == null ? current.showOnCreate() : s.showOnCreate())
                .param("onDetail", s.showOnDetail() == null ? current.showOnDetail() : s.showOnDetail())
                .param("searchable", s.searchable() == null ? current.searchable() : s.searchable() && current.dataType().isQuickSearchable())
                .param("filterable", s.filterable() == null ? current.filterable() : s.filterable())
                .param("sortable", s.sortable() == null ? current.sortable()
                        : s.sortable() && current.dataType() != DataType.MULTI_PICKLIST)
                .param("editable", s.editableAfterCreate() == null ? current.editableAfterCreate() : s.editableAfterCreate())
                .param("section", section)
                .param("sortOrder", nextSortOrder)
                .param("rules", toJson(displayRules == null ? Map.of() : displayRules))
                .param("arm", arm)
                .param("user", currentUser.id())
                .param("id", id)
                .param("version", u.version())
                .update();
        if (n == 0) {
            throw new OptimisticLockingFailureException("Custom field " + id + " changed");
        }
        if (current.picklist() != null && !current.system() && !current.label().equals(u.label().trim())) {
            jdbc.sql("UPDATE picklist_set SET name = :name WHERE code = :code")
                    .param("name", u.label().trim()).param("code", current.picklist()).update();
        }
        if (!section.equalsIgnoreCase(current.section())) {
            syncFieldOrderBySections(current.object());
        }
        audit.record("custom_field_def", id, "update", current, u, null);
        evict();
        return loadOne(id);
    }

    /** Disabling hides the field on forms, lists and filters; stored values stay and come back if it is enabled. */
    @Transactional
    public CustomFieldDef setActive(UUID id, boolean active) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "retire or reactivate custom fields");
        CustomFieldDef current = get(id);
        requireLive(current);
        if (!active && current.requiredLocked()) {
            throw new ConflictException("field-locked", "'" + current.label() + "' is required by the CRM's rules, so it "
                    + "can't be disabled. You can relabel it.", Map.of("field", "active"));
        }
        jdbc.sql("""
                        UPDATE custom_field_def SET active = :active, updated_at = now(), updated_by = :user,
                            version = version + 1 WHERE id = :id""")
                .param("active", active).param("user", currentUser.id()).param("id", id).update();
        audit.record("custom_field_def", id, active ? "reactivate" : "retire",
                Map.of("active", current.active()), Map.of("active", active), null);
        evict();
        return loadOne(id);
    }

    @Transactional
    public List<CustomFieldDef> move(UUID id, int direction) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "reorder custom fields");
        CustomFieldDef target = get(id);
        List<CustomFieldDef> all = new ArrayList<>(list(target.object()));
        List<CustomFieldDef> sameSection = all.stream()
                .filter(f -> f.section().equalsIgnoreCase(target.section()))
                .toList();
        int i = sameSection.indexOf(target);
        int j = i + Integer.signum(direction);
        if (direction != 0 && i >= 0 && j >= 0 && j < sameSection.size()) {
            CustomFieldDef other = sameSection.get(j);
            int ai = all.indexOf(target);
            int aj = all.indexOf(other);
            all.set(ai, other);
            all.set(aj, target);
            writeOrder(all.stream().map(CustomFieldDef::id).toList(), "sort_order");
            audit.record("custom_field_def", id, "move", null, Map.of("direction", direction), null);
            evict();
        }
        return list(target.object());
    }

    /**
     * Sets the whole form order (or list column order) of a record type in one call. Fields not listed keep
     * their relative order after the listed ones.
     */
    @Transactional
    public List<CustomFieldDef> order(String object, List<UUID> fieldIds, boolean listOrder) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "reorder fields");
        List<CustomFieldDef> fields = list(recordKey(object));
        Set<UUID> known = new LinkedHashSet<>(fields.stream().map(CustomFieldDef::id).toList());
        Set<UUID> requested = new LinkedHashSet<>(fieldIds);
        if (requested.size() != fieldIds.size() || !known.containsAll(requested)) {
            throw new FieldValidationException(Map.of("fieldIds", "List each field of this record type once."));
        }
        List<UUID> ordered = new ArrayList<>(requested);
        fields.stream()
                .sorted((a, b) -> listOrder ? Integer.compare(a.listOrder(), b.listOrder()) : Integer.compare(a.sortOrder(), b.sortOrder()))
                .map(CustomFieldDef::id).filter(fid -> !requested.contains(fid)).forEach(ordered::add);
        writeOrder(ordered, listOrder ? "list_order" : "sort_order");
        audit.record("custom_field_def", null, listOrder ? "order-list" : "order",
                null, Map.of("object", object, "fieldIds", ordered), null);
        evict();
        return list(object);
    }

    /** How many records (live and archived) hold a value for the field. */
    @Transactional(readOnly = true)
    public FieldUsage usage(UUID id) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "see field usage");
        CustomFieldDef d = get(id);
        long[] counts = countValues(d);
        return new FieldUsage(d.id(), d.label(), d.deletable(), counts[0], counts[1]);
    }

    /**
     * Deletes a field. By default this is a soft delete: the field disappears from every form, list, filter and
     * rule, its values stay on the records, and it is listed under Deleted fields where an admin can restore it.
     * {@code permanent} deletes a field that is already soft-deleted for good: its values are removed from every
     * record (archived ones included) and each changed record's version moves on, all in one transaction. Past
     * audit rows are kept. The definition row is locked first, so a concurrent save of the field waits for this.
     */
    @Transactional
    public Map<String, Object> delete(UUID id, long version, boolean permanent) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "delete custom fields");
        Long locked = jdbc.sql("SELECT version FROM custom_field_def WHERE id = :id FOR UPDATE")
                .param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Custom field", id));
        evict();
        CustomFieldDef d = get(id);
        if (!d.deletable()) {
            throw new ConflictException("system-field", "'" + d.label() + "' is a system field and can't be deleted. "
                    + "You can relabel or disable it.", Map.of());
        }
        if (locked != version) {
            throw new OptimisticLockingFailureException("Custom field " + id + " changed");
        }
        long[] counts = countValues(d);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", d.apiName());
        if (!permanent) {
            if (!d.deleted()) {
                jdbc.sql("""
                                UPDATE custom_field_def SET deleted_at = now(), deleted_by = :user, updated_at = now(),
                                    updated_by = :user, version = version + 1 WHERE id = :id""")
                        .param("user", currentUser.id()).param("id", id).update();
                audit.record("custom_field_def", id, "delete", Map.of("label", d.label()),
                        Map.of("recordsWithValue", counts[0], "archivedRecordsWithValue", counts[1]), null);
                evict();
            }
            result.put("permanent", false);
            result.put("valuesKept", counts[0] + counts[1]);
            return result;
        }
        if (!d.deleted()) {
            throw new ConflictException("not-deleted", "Delete '" + d.label() + "' first. A field can be deleted "
                    + "permanently only from Deleted fields.", Map.of());
        }
        int valuesDeleted = 0;
        if (counts[0] + counts[1] > 0) {
            valuesDeleted = jdbc.sql("""
                            UPDATE %s SET custom_fields = custom_fields - CAST(:api AS text),
                                updated_at = now(), updated_by = :user, version = version + 1
                            WHERE (custom_fields -> :api) IS NOT NULL""".formatted(recordTable(d.object())))
                    .param("api", d.apiName()).param("user", currentUser.id())
                    .update();
        }
        jdbc.sql("DELETE FROM custom_field_def WHERE id = :id").param("id", id).update();
        String picklistDeleted = null;
        if (d.picklist() != null && d.picklist().startsWith("CF_")
                && jdbc.sql("SELECT count(*) FROM custom_field_def WHERE picklist_set_code = :c")
                .param("c", d.picklist()).query(Long.class).single() == 0) {
            jdbc.sql("DELETE FROM picklist_value WHERE set_code = :c").param("c", d.picklist()).update();
            jdbc.sql("DELETE FROM picklist_set WHERE code = :c").param("c", d.picklist()).update();
            picklistDeleted = d.picklist();
        }
        audit.record("custom_field_def", id, "delete-permanently", d, Map.of("valuesDeleted", valuesDeleted), null);
        evict();
        result.put("permanent", true);
        result.put("valuesDeleted", valuesDeleted);
        result.put("picklistDeleted", picklistDeleted);
        return result;
    }

    /** Brings a deleted field back exactly as it was (enabled or disabled), with every value it kept. */
    @Transactional
    public CustomFieldDef restore(UUID id) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "restore deleted fields");
        jdbc.sql("SELECT id FROM custom_field_def WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Custom field", id));
        evict();
        CustomFieldDef d = get(id);
        if (!d.deleted()) {
            return d;
        }
        if (labelTaken(d.object(), d.label(), id)) {
            throw new ConflictException("label-taken", "A field called '" + d.label() + "' exists now. Rename that field, "
                    + "then restore this one.", Map.of());
        }
        jdbc.sql("""
                        UPDATE custom_field_def SET deleted_at = NULL, deleted_by = NULL, updated_at = now(),
                            updated_by = :user, version = version + 1 WHERE id = :id""")
                .param("user", currentUser.id()).param("id", id).update();
        audit.record("custom_field_def", id, "restore", null, Map.of("label", d.label()), null);
        evict();
        return loadOne(id);
    }

    /** Setup → Deleted fields: deleted fields of a record type, newest first, with the values they still keep. */
    @Transactional(readOnly = true)
    public List<DeletedField> deleted(String object) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "see deleted fields");
        recordTable(object);
        Map<String, String> users = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM app_user").query(rs -> {
            users.put(rs.getString(1), rs.getString(2));
        });
        return list(object).stream()
                .filter(CustomFieldDef::deleted)
                .sorted((a, b) -> b.deletedAt().compareTo(a.deletedAt()))
                .map(d -> {
                    long[] counts = countValues(d);
                    return new DeletedField(d, users.getOrDefault(d.deletedBy(), d.deletedBy()), counts[0], counts[1]);
                })
                .toList();
    }

    /** Changes to a deleted field go through Restore first. */
    private static void requireLive(CustomFieldDef d) {
        if (d.deleted()) {
            throw new ConflictException("field-deleted", "'" + d.label() + "' is deleted. Restore it from Deleted fields "
                    + "to change it.", Map.of());
        }
    }

    /** Relabels a system field from the original Setup label screen. */
    @Transactional
    public void relabel(String object, String apiName, String label) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, "relabel fields");
        String api = switch (apiName) {
            case "emails" -> "email";
            case "phones" -> "mobile";
            default -> apiName;
        };
        CustomFieldDef d = list(recordKey(object)).stream().filter(f -> f.apiName().equals(api)).findFirst()
                .orElseThrow(() -> new NotFoundException("Field", object + "." + apiName));
        if (labelTaken(object, label, d.id())) {
            throw new FieldValidationException(Map.of("label", "A field with this label already exists."));
        }
        jdbc.sql("""
                        UPDATE custom_field_def SET label = :label, updated_at = now(), updated_by = :user,
                            version = version + 1 WHERE id = :id""")
                .param("label", label.trim()).param("user", currentUser.id()).param("id", d.id()).update();
        audit.record("custom_field_def", d.id(), "update", Map.of("label", d.label()), Map.of("label", label.trim()), null);
        evict();
    }

    /** True when this custom field's dropdown options may be changed by the current user. */
    public boolean isCustomPicklist(String setCode) {
        return jdbc.sql("SELECT count(*) FROM custom_field_def WHERE picklist_set_code = :c AND system_key IS NULL")
                .param("c", setCode).query(Long.class).single() > 0;
    }

    private long[] countValues(CustomFieldDef d) {
        if (d.storage() != Storage.CUSTOM) {
            return new long[] {0, 0};
        }
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE archived_at IS NULL), count(*) FILTER (WHERE archived_at IS NOT NULL)
                        FROM %s WHERE (custom_fields -> :api) IS NOT NULL""".formatted(recordTable(d.object())))
                .param("api", d.apiName())
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
    }

    /** Groups all fields of an object in section order while keeping each section's relative field order intact. */
    void syncFieldOrderBySections(String object) {
        List<String> sectionKeys = jdbc.sql("""
                        SELECT lower(btrim(name)) FROM custom_field_section
                        WHERE object_api_name = :obj ORDER BY sort_order, name""")
                .param("obj", object)
                .query(String.class)
                .list();
        Map<String, List<UUID>> buckets = new LinkedHashMap<>();
        for (String key : sectionKeys) {
            buckets.put(key, new ArrayList<>());
        }
        List<UUID> unbucketed = new ArrayList<>();
        jdbc.sql("""
                        SELECT id, COALESCE(section, '') AS section
                        FROM custom_field_def
                        WHERE object_api_name = :obj
                        ORDER BY sort_order, label""")
                .param("obj", object)
                .query(rs -> {
                    UUID fid = rs.getObject("id", UUID.class);
                    String secKey = rs.getString("section").trim().toLowerCase();
                    List<UUID> bucket = buckets.get(secKey);
                    if (bucket != null) {
                        bucket.add(fid);
                    } else {
                        unbucketed.add(fid);
                    }
                });
        List<UUID> ordered = new ArrayList<>();
        buckets.values().forEach(ordered::addAll);
        ordered.addAll(unbucketed);
        writeOrder(ordered, "sort_order");
    }

    private void writeOrder(List<UUID> ids, String column) {
        for (int k = 0; k < ids.size(); k++) {
            jdbc.sql("UPDATE custom_field_def SET %s = :sort WHERE id = :id".formatted(column))
                    .param("sort", k).param("id", ids.get(k)).update();
        }
    }

    private CustomFieldDef loadOne(UUID id) {
        return loadDefinitions(null).stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Custom field", id));
    }

    /** Clears cached definitions now and again after commit, so no request caches a half-done change. */
    void evict() {
        cache.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.clear();
                }
            });
        }
    }

    /* ====================================================================================================
     * Values
     * ==================================================================================================== */

    /** CUSTOM values of one record, keyed by api name. Sensitive values are left out without permission. */
    public Map<String, Object> read(String object, UUID recordId) {
        return readMany(object, List.of(recordId)).getOrDefault(recordId, new LinkedHashMap<>());
    }

    /** CUSTOM values of many records in one query (used by lists), keyed by record id then api name. */
    public Map<UUID, Map<String, Object>> readMany(String object, Collection<UUID> recordIds) {
        return load(object, recordIds, permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE));
    }

    /** Typed CUSTOM values from a stored custom_fields document, as {@link #read} returns them. */
    public Map<String, Object> decode(String object, String document) {
        return fromDocument(customDefs(object), document, permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE));
    }

    private List<CustomFieldDef> customDefs(String object) {
        return list(object).stream().filter(d -> d.storage() == Storage.CUSTOM).toList();
    }

    private Map<UUID, Map<String, Object>> load(String object, Collection<UUID> recordIds, boolean includeSensitive) {
        String table = recordTable(object);
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        if (recordIds.isEmpty()) {
            return out;
        }
        List<CustomFieldDef> defs = customDefs(object);
        jdbc.sql("SELECT id, custom_fields::text AS custom_fields FROM %s WHERE id IN (:ids)".formatted(table))
                .param("ids", List.copyOf(recordIds))
                .query(rs -> {
                    out.put(rs.getObject("id", UUID.class),
                            fromDocument(defs, rs.getString("custom_fields"), includeSensitive));
                });
        return out;
    }

    /** Turns a stored document into typed values, in the fields' display order. */
    private Map<String, Object> fromDocument(List<CustomFieldDef> defs, String document, boolean includeSensitive) {
        Map<?, ?> stored = (Map<?, ?>) parseJson(document);
        Map<String, Object> values = new LinkedHashMap<>();
        for (CustomFieldDef d : defs) {
            Object raw = stored.get(d.apiName());
            if (raw == null || (d.sensitive() && !includeSensitive)) {
                continue;
            }
            Object value = switch (d.dataType()) {
                case NUMBER, CURRENCY, PERCENT -> plain(new BigDecimal(raw.toString()));
                case MULTI_PICKLIST -> ((List<?>) raw).stream().map(String::valueOf).toList();
                default -> raw;
            };
            if (!isEmpty(value)) {
                values.put(d.apiName(), value);
            }
        }
        return values;
    }

    /** Locks the record's row for this transaction, so two saves of its custom values can't interleave. */
    private void lock(String table, UUID recordId) {
        jdbc.sql("SELECT id FROM %s WHERE id = :id FOR UPDATE".formatted(table))
                .param("id", recordId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Record", recordId));
    }

    public record Change(Map<String, Object> before, Map<String, Object> after) {
        public boolean isEmpty() {
            return after.isEmpty();
        }
    }

    /** A checked set of CUSTOM changes, ready to {@link #persist}. */
    public record Prepared(String object, Change change, Map<String, String> errors) {
    }

    /**
     * Checks and saves CUSTOM values on one record, inside the caller's transaction. Used by Entity; Person
     * calls {@link #prepare} and {@link #persist} itself so CORE and CUSTOM errors come back together.
     */
    @Transactional
    public Change write(String object, UUID recordId, Map<String, Object> patch, boolean creating) {
        lock(recordTable(object), recordId);
        Map<String, Object> current = load(object, List.of(recordId), true).getOrDefault(recordId, Map.of());
        Prepared p = prepare(object, current, patch, creating, Set.of());
        throwIfAny(p.errors());
        persist(recordId, p);
        return p.change();
    }

    /** CUSTOM values of one record including sensitive ones, for a write that has already locked the row. */
    public Map<String, Object> currentForWrite(String object, UUID recordId) {
        return load(object, List.of(recordId), true).getOrDefault(recordId, Map.of());
    }

    /**
     * Checks CUSTOM changes against the record's current CUSTOM values.
     * <ol>
     *   <li>On create, defaults fill fields the caller left out.</li>
     *   <li>Each value is converted to its field type; unknown, disabled or forbidden fields are refused; fields
     *       that can't change after create are refused on update.</li>
     *   <li>Mandatory fields must have a value once the change is applied.</li>
     * </ol>
     * Keys in {@code otherKeys} belong to other storages and are skipped. Errors are returned, not thrown.
     */
    public Prepared prepare(String object, Map<String, Object> current, Map<String, Object> patch, boolean creating,
                            Set<String> otherKeys) {
        Map<String, CustomFieldDef> defs = new LinkedHashMap<>();
        customDefs(object).forEach(d -> defs.put(d.apiName(), d));
        boolean canSensitive = permissions.has(PermissionService.CUSTOM_FIELD_SENSITIVE);

        Map<String, Object> incoming = new LinkedHashMap<>(patch == null ? Map.of() : patch);
        otherKeys.forEach(incoming::remove);
        if (creating) {
            for (CustomFieldDef d : defs.values()) {
                if (d.active() && d.defaultValue() != null && !incoming.containsKey(d.apiName())
                        && (!d.sensitive() || canSensitive)) {
                    incoming.put(d.apiName(), d.defaultValue());
                }
            }
        }

        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String key = e.getKey();
            CustomFieldDef def = defs.get(key);
            if (def == null) {
                errors.put(key, "There is no " + object + " custom field called '" + key + "'.");
                continue;
            }
            if (def.sensitive() && !canSensitive) {
                throw new ForbiddenException("You don't have permission to change '" + def.label() + "'.");
            }
            if (def.computed()) {
                if (!isEmpty(e.getValue()) && !sameValue(e.getValue(), current.get(key))) {
                    errors.put(key, "'" + def.label() + "' is set by the CRM, so it can't be typed in.");
                }
                continue;
            }
            Object normalized = normalize(def, e.getValue(), current.get(key), errors, key);
            if (errors.containsKey(key) || sameValue(normalized, current.get(key))) {
                continue;
            }
            if (!def.active()) {
                errors.put(key, "'" + def.label() + "' has been retired, so it can't be changed.");
                continue;
            }
            if (!creating && !def.editableAfterCreate()) {
                errors.put(key, "'" + def.label() + "' can't be changed after the record is saved.");
                continue;
            }
            before.put(key, current.get(key));
            after.put(key, normalized);
        }

        // Mandatory: on create, every active mandatory field the user may see must have a value. On edit,
        // untouched fields that were already empty (e.g. legacy records or fields made mandatory later)
        // must not block unrelated edits, while clearing an existing value or sending blank still fails.
        Map<String, Object> resulting = new LinkedHashMap<>(current);
        after.forEach((k, v) -> {
            if (v == null) {
                resulting.remove(k);
            } else {
                resulting.put(k, v);
            }
        });
        for (CustomFieldDef d : defs.values()) {
            if (d.active() && d.mandatory() && !d.computed() && (!d.sensitive() || canSensitive)
                    && (creating || incoming.containsKey(d.apiName()) || !isEmpty(current.get(d.apiName())))
                    && isEmpty(resulting.get(d.apiName())) && !errors.containsKey(d.apiName())) {
                errors.put(d.apiName(), d.label() + " is required.");
            }
        }
        return new Prepared(object, new Change(before, after), errors);
    }

    /** Writes prepared CUSTOM changes: changed keys merged in, cleared keys removed, other keys left alone. */
    public void persist(UUID recordId, Prepared p) {
        if (p.change().isEmpty()) {
            return;
        }
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> cleared = new ArrayList<>();
        p.change().after().forEach((k, v) -> {
            if (v == null) {
                cleared.add(k);
            } else {
                set.put(k, v);
            }
        });
        jdbc.sql("""
                        UPDATE %s SET custom_fields = (custom_fields || CAST(:set AS jsonb))
                            - ARRAY(SELECT jsonb_array_elements_text(CAST(:cleared AS jsonb)))
                        WHERE id = :id""".formatted(recordTable(p.object())))
                .param("set", toJson(set)).param("cleared", toJson(cleared)).param("id", recordId)
                .update();
    }

    /**
     * Converts one incoming value to its stored form, or records an error under {@code key}. Null means "clear".
     * Used for every storage: CORE and CHILD Person fields go through the same type rules.
     */
    public Object normalize(CustomFieldDef def, Object raw, Object previous, Map<String, String> errors, String key) {
        if (raw == null || (raw instanceof String s && s.isBlank()) || (raw instanceof List<?> l && l.isEmpty())) {
            return null;
        }
        Object value = normalizeType(def, raw, previous, errors, key);
        if (value != null && !errors.containsKey(key)) {
            checkRules(def, value, errors, key);
        }
        return errors.containsKey(key) ? null : value;
    }

    private Object normalizeType(CustomFieldDef def, Object raw, Object previous, Map<String, String> errors, String key) {
        switch (def.dataType()) {
            case TEXT, LONG_TEXT -> {
                if (!(raw instanceof String s)) {
                    return fail(errors, key, "Enter text.");
                }
                // An ABN or ACN is kept as its digits, so "51 824 753 556" and "51824753556" are the same number.
                if (def.validation() != null && FORMATS.contains(String.valueOf(def.validation().get("format")))
                        && s.trim().matches("[\\d ]+")) {
                    return Formats.digits(s);
                }
                int max = def.maxLength() != null ? def.maxLength()
                        : def.dataType() == DataType.TEXT ? DEFAULT_TEXT_LENGTH : DEFAULT_LONG_TEXT_LENGTH;
                String v = def.dataType() == DataType.TEXT ? s.trim() : s;
                return v.length() > max ? fail(errors, key, "Use at most " + max + " characters (this has " + v.length() + ").") : v;
            }
            case EMAIL -> {
                return raw instanceof String s && Formats.isValidEmail(s) && s.trim().length() <= 320 ? s.trim()
                        : fail(errors, key, "This email address is not in a valid format.");
            }
            case PHONE -> {
                if (!(raw instanceof String s) || !PHONE.matcher(s.trim()).matches()) {
                    return fail(errors, key, "Enter a phone number using digits, spaces, +, ( ) or -.");
                }
                int digits = Formats.digits(s).length();
                return digits >= 6 && digits <= 15 ? s.trim() : fail(errors, key, "A phone number has 6 to 15 digits.");
            }
            case URL -> {
                if (!(raw instanceof String s) || s.trim().contains(" ") || !s.contains(".") || s.length() > 2048) {
                    return fail(errors, key, "Enter a web address, e.g. example.com.au.");
                }
                return s.trim();
            }
            case NUMBER, CURRENCY, PERCENT -> {
                BigDecimal n;
                try {
                    n = raw instanceof Number num ? new BigDecimal(num.toString())
                            : new BigDecimal(String.valueOf(raw).trim().replace(",", ""));
                } catch (NumberFormatException ex) {
                    return fail(errors, key, "Enter a number.");
                }
                int decimals = def.decimalPlaces() != null ? def.decimalPlaces() : DEFAULT_DECIMALS;
                n = plain(n);
                if (n.scale() > decimals) {
                    return fail(errors, key, decimals == 0 ? "Enter a whole number." : "Use at most " + decimals + " decimal places.");
                }
                if (n.abs().compareTo(MAX_NUMBER) >= 0) {
                    return fail(errors, key, "This number is too large.");
                }
                return n;
            }
            case DATE -> {
                try {
                    return LocalDate.parse(String.valueOf(raw).trim()).toString();
                } catch (DateTimeParseException ex) {
                    return fail(errors, key, "Enter a date as YYYY-MM-DD.");
                }
            }
            case CHECKBOX -> {
                if (!(raw instanceof Boolean b)) {
                    return fail(errors, key, "Use true or false.");
                }
                return b ? Boolean.TRUE : null; // an unticked checkbox is stored as "no value"
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                String s = String.valueOf(raw).trim().toLowerCase();
                return switch (s) {
                    case "true", "yes" -> Boolean.TRUE;
                    case "false", "no" -> Boolean.FALSE;
                    default -> fail(errors, key, "Choose Yes or No.");
                };
            }
            case PICKLIST -> {
                if (!(raw instanceof String code)) {
                    return fail(errors, key, "Choose an option from the list.");
                }
                metadata.checkCode(errors, key, def.picklist(), code, previous instanceof String p ? p : null);
                return code;
            }
            case MULTI_PICKLIST -> {
                if (!(raw instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
                    return fail(errors, key, "Choose options from the list.");
                }
                Set<String> codes = new LinkedHashSet<>();
                list.forEach(v -> codes.add((String) v));
                List<String> prev = previous instanceof List<?> pl ? pl.stream().map(String::valueOf).toList() : List.of();
                metadata.checkCodes(errors, key, def.picklist(), codes, prev);
                return List.copyOf(codes);
            }
            case USER -> {
                if (!(raw instanceof String id) || !metadata.userExists(id.trim())) {
                    return fail(errors, key, "Pick an owner from the user list.");
                }
                return id.trim();
            }
            case PHONE_LIST, EMAIL_LIST -> {
                return normalizeList(def, raw, previous, errors, key);
            }
            default -> {
                return fail(errors, key, "Unsupported field type.");
            }
        }
    }

    /** The admin's extra rules: minimum length, minimum/maximum number, pattern. */
    private void checkRules(CustomFieldDef def, Object value, Map<String, String> errors, String key) {
        Map<String, Object> v = def.validation();
        if (v == null || v.isEmpty()) {
            return;
        }
        if (value instanceof String s && "ABN".equals(v.get("format")) && Formats.abnError(s) != null) {
            errors.put(key, Formats.abnError(s));
            return;
        }
        if (value instanceof String s && "ACN".equals(v.get("format")) && Formats.acnError(s) != null) {
            errors.put(key, Formats.acnError(s));
            return;
        }
        if (value instanceof String s && def.dataType().isText()) {
            if (v.get("minLength") instanceof Number min && s.length() < min.intValue()) {
                errors.put(key, "Use at least " + min.intValue() + " characters.");
                return;
            }
            if (v.get("pattern") instanceof String pattern && !pattern.isEmpty() && !Pattern.compile(pattern).matcher(s).matches()) {
                errors.put(key, v.get("patternMessage") instanceof String m && !m.isBlank() ? m : "This value is not in the expected format.");
            }
        }
        if (value instanceof BigDecimal n) {
            if (v.get("min") instanceof Number min && n.compareTo(new BigDecimal(min.toString())) < 0) {
                errors.put(key, "Enter a number of " + plain(new BigDecimal(min.toString())).toPlainString() + " or more.");
            } else if (v.get("max") instanceof Number max && n.compareTo(new BigDecimal(max.toString())) > 0) {
                errors.put(key, "Enter a number of " + plain(new BigDecimal(max.toString())).toPlainString() + " or less.");
            }
        }
    }

    /* ====================================================================================================
     * Helpers
     * ==================================================================================================== */

    /**
     * Converts a value using a type's standard rules (default length and decimals), recording any error
     * under {@code key}. Used by one-off record fields, which share the type rules but have no definition row.
     */
    public Object normalizeForType(DataType type, Object raw, Map<String, String> errors, String key) {
        return normalize(CustomFieldDef.probe(null, key, key, type, null, null, null, null), raw, null, errors, key);
    }

    /** Checks a default against the field's own rules. Dropdown defaults may be given as a label or a code. */
    private Object resolveDefault(String object, DataType type, String setCode, Integer maxLength, Integer decimals,
                                  Map<String, Object> validation, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank()) || (raw instanceof List<?> l && l.isEmpty())) {
            return null;
        }
        Object candidate = raw;
        if (type == DataType.PICKLIST && raw instanceof String s) {
            candidate = optionCode(setCode, s);
        } else if (type == DataType.MULTI_PICKLIST && raw instanceof List<?> list) {
            candidate = list.stream().map(v -> optionCode(setCode, String.valueOf(v))).toList();
        }
        CustomFieldDef probe = CustomFieldDef.probe(object, "defaultValue", "Default value", type, setCode, maxLength,
                decimals, validation);
        Map<String, String> errors = new LinkedHashMap<>();
        Object normalized = normalize(probe, candidate, null, errors, "defaultValue");
        throwIfAny(errors);
        return normalized;
    }

    /** Checks layout settings and validation rules; returns the cleaned rules. */
    private static Map<String, Object> checkSettings(Map<String, String> errors, DataType type, FieldSettings s) {
        if (s.width() != null && !s.width().equals("HALF") && !s.width().equals("FULL")) {
            errors.put("width", "Width is HALF or FULL.");
        }
        if (s.placeholder() != null && s.placeholder().length() > 150) {
            errors.put("placeholder", "Use at most 150 characters.");
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        if (s.validation() == null) {
            return rules;
        }
        for (Map.Entry<String, Object> e : s.validation().entrySet()) {
            if (e.getValue() == null || (e.getValue() instanceof String str && str.isBlank())) {
                continue;
            }
            if (!VALIDATION_KEYS.contains(e.getKey())) {
                errors.put("validation", "Unknown rule '" + e.getKey() + "'.");
                return rules;
            }
            rules.put(e.getKey(), e.getValue());
        }
        boolean text = type.isText();
        if (rules.containsKey("format") && (type != DataType.TEXT || !FORMATS.contains(String.valueOf(rules.get("format"))))) {
            errors.put("validation", "The format rule is ABN or ACN, on a text field.");
        }
        if ((rules.containsKey("minLength") || rules.containsKey("pattern")) && !text) {
            errors.put("validation", "Length and pattern rules apply to text fields only.");
        }
        if ((rules.containsKey("min") || rules.containsKey("max")) && !type.isNumeric()) {
            errors.put("validation", "Minimum and maximum apply to number fields only.");
        }
        for (String k : List.of("minLength", "min", "max")) {
            if (rules.containsKey(k) && !(rules.get(k) instanceof Number)) {
                errors.put("validation", "'" + k + "' must be a number.");
            }
        }
        if (rules.get("min") instanceof Number min && rules.get("max") instanceof Number max
                && new BigDecimal(min.toString()).compareTo(new BigDecimal(max.toString())) > 0) {
            errors.put("validation", "The minimum can't be more than the maximum.");
        }
        if (rules.get("pattern") instanceof String pattern) {
            if (pattern.length() > MAX_PATTERN_LENGTH) {
                errors.put("validation", "Keep the pattern under " + MAX_PATTERN_LENGTH + " characters.");
            } else {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    errors.put("validation", "The pattern is not a valid regular expression.");
                }
            }
        } else if (rules.containsKey("pattern")) {
            errors.put("validation", "The pattern must be text.");
        }
        return rules;
    }

    /**
     * A phone or email list: [{label, value, primary}]. Labels come from the field's list (a retired label the
     * record already had stays allowed), values follow the phone or email rules, a value appears once, and exactly
     * one item is primary (the first one when none is marked).
     */
    private Object normalizeList(CustomFieldDef def, Object raw, Object previous, Map<String, String> errors, String key) {
        if (!(raw instanceof List<?> list)) {
            return fail(errors, key, "Send a list of {label, value, primary} items.");
        }
        if (list.size() > MAX_LIST_ITEMS) {
            return fail(errors, key, "Add at most " + MAX_LIST_ITEMS + " items.");
        }
        Set<String> previousLabels = new LinkedHashSet<>();
        if (previous instanceof List<?> pl) {
            pl.forEach(i -> {
                if (i instanceof Map<?, ?> m && m.get("label") != null) {
                    previousLabels.add(String.valueOf(m.get("label")));
                }
            });
        }
        String noun = def.dataType() == DataType.PHONE_LIST ? "phone number" : "email address";
        CustomFieldDef item = CustomFieldDef.probe(def.object(), key, def.label(), def.dataType().itemType(), null, null, null, null);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int primaries = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return fail(errors, key, "Each " + noun + " needs a label and a value.");
            }
            Object value = m.get("value");
            if (isEmpty(value)) {
                continue; // an empty row on the form is ignored
            }
            String label = m.get("label") == null ? null : String.valueOf(m.get("label"));
            if (Formats.isBlank(label)) {
                return fail(errors, key, "Choose a label for " + value + ".");
            }
            metadata.checkCode(errors, key, def.picklist(), label, previousLabels.contains(label) ? label : null);
            if (errors.containsKey(key)) {
                return null;
            }
            Object normalized = normalize(item, value, null, errors, key);
            if (errors.containsKey(key)) {
                errors.put(key, value + ": " + errors.get(key));
                return null;
            }
            String dedupe = def.dataType() == DataType.PHONE_LIST ? Formats.digits(String.valueOf(normalized))
                    : String.valueOf(normalized).toLowerCase();
            if (!seen.add(dedupe)) {
                return fail(errors, key, normalized + " is listed twice.");
            }
            boolean primary = Boolean.TRUE.equals(m.get("primary"));
            primaries += primary ? 1 : 0;
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("label", label);
            clean.put("value", normalized);
            clean.put("primary", primary);
            out.add(clean);
        }
        if (out.isEmpty()) {
            return null;
        }
        if (primaries > 1) {
            return fail(errors, key, "Mark one " + noun + " as primary.");
        }
        if (primaries == 0) {
            out.getFirst().put("primary", true);
        }
        return out;
    }

    /** The primary item's value of a phone or email list (or the value itself for a single phone or email). */
    public static String primaryValue(Object value) {
        if (value instanceof List<?> l) {
            return l.stream().filter(i -> i instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("primary")))
                    .map(i -> String.valueOf(((Map<?, ?>) i).get("value"))).findFirst().orElse(null);
        }
        return value == null ? null : String.valueOf(value);
    }

    /** Every item value of a phone or email list, optionally only items with one of the given labels. */
    public static List<String> listValues(Object value, Set<String> labels) {
        if (!(value instanceof List<?> l)) {
            return value == null ? List.of() : List.of(String.valueOf(value));
        }
        return l.stream().filter(i -> i instanceof Map<?, ?> m && (labels == null || labels.contains(String.valueOf(m.get("label")))))
                .map(i -> String.valueOf(((Map<?, ?>) i).get("value"))).toList();
    }

    /** Checks {"visibleWhen": [...], "requiredWhen": [...]}; each condition names a field of the same record type. */
    private Map<String, Object> checkDisplayRules(Map<String, String> errors, String object, Map<String, Object> rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rules == null) {
            return out;
        }
        Set<String> fields = new LinkedHashSet<>();
        if (RECORD_TABLE.containsKey(object)) {
            list(object).stream().filter(d -> !d.deleted()).forEach(d -> fields.add(d.apiName()));
        }
        for (Map.Entry<String, Object> e : rules.entrySet()) {
            if (!e.getKey().equals("visibleWhen") && !e.getKey().equals("requiredWhen")) {
                errors.put("displayRules", "Use visibleWhen and requiredWhen.");
                return out;
            }
            if (e.getValue() == null) {
                continue;
            }
            if (!(e.getValue() instanceof List<?> conditions) || conditions.size() > 5) {
                errors.put("displayRules", "Use a list of at most 5 conditions.");
                return out;
            }
            List<Map<String, Object>> clean = new ArrayList<>();
            for (Object o : conditions) {
                if (!(o instanceof Map<?, ?> c) || !(c.get("field") instanceof String f) || !(c.get("operator") instanceof String op)) {
                    errors.put("displayRules", "Each condition needs a field and an operator.");
                    return out;
                }
                if (!fields.contains(f) && !CONTEXT_FIELDS.contains(f)) {
                    errors.put("displayRules", "'" + f + "' isn't a field of this record type.");
                    return out;
                }
                if (!CONDITION_OPERATORS.contains(op)) {
                    errors.put("displayRules", "'" + op + "' can't be used in a condition.");
                    return out;
                }
                boolean needsValue = !op.startsWith("IS_");
                if (needsValue && isEmpty(c.get("value"))) {
                    errors.put("displayRules", "Give a value for each condition on '" + f + "'.");
                    return out;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", f);
                m.put("operator", op);
                if (needsValue) {
                    m.put("value", c.get("value"));
                }
                clean.add(m);
            }
            if (!clean.isEmpty()) {
                out.put(e.getKey(), clean);
            }
        }
        return out;
    }

    private static boolean mentions(Map<String, Object> rules, String apiName) {
        return rules.values().stream().filter(List.class::isInstance).flatMap(l -> ((List<?>) l).stream())
                .anyMatch(c -> c instanceof Map<?, ?> m && apiName.equals(m.get("field")));
    }

    private String checkOptionsFrom(Map<String, String> errors, String object, DataType type, String optionsFrom) {
        if (Formats.isBlank(optionsFrom)) {
            return null;
        }
        if (type != DataType.PHONE && type != DataType.EMAIL) {
            errors.put("optionsFrom", "Only a phone or email field can pick from a list on the record.");
            return null;
        }
        DataType wanted = type == DataType.PHONE ? DataType.PHONE_LIST : DataType.EMAIL_LIST;
        boolean ok = RECORD_TABLE.containsKey(object) && list(object).stream()
                .anyMatch(d -> !d.deleted() && d.apiName().equals(optionsFrom) && d.dataType() == wanted);
        if (!ok) {
            errors.put("optionsFrom", "Choose a " + (type == DataType.PHONE ? "phone" : "email") + " list field of this record type.");
            return null;
        }
        return optionsFrom;
    }

    private static String checkArm(Map<String, String> errors, String arm) {
        if (arm == null) {
            return null;
        }
        if (!ARMS.contains(arm)) {
            errors.put("arm", "The arm is CREDIT, CAPITAL or BOTH.");
            return null;
        }
        return arm;
    }


    private String optionCode(String setCode, String labelOrCode) {
        return jdbc.sql("""
                        SELECT code FROM picklist_value WHERE set_code = :s AND (code = :v OR lower(label) = lower(:v))
                        ORDER BY (code = :v) DESC LIMIT 1""")
                .param("s", setCode).param("v", labelOrCode.trim())
                .query(String.class).optional().orElse(labelOrCode.trim());
    }

    private boolean labelTaken(String object, String label, UUID ignoreId) {
        return jdbc.sql("""
                        SELECT count(*) FROM custom_field_def
                        WHERE object_api_name = :obj AND lower(label) = lower(:label)
                          AND deleted_at IS NULL
                          AND (CAST(:id AS uuid) IS NULL OR id <> CAST(:id AS uuid))""")
                .param("obj", object).param("label", label.trim()).param("id", ignoreId, java.sql.Types.OTHER)
                .query(Long.class).single() > 0;
    }

    private Object parseJson(String text) {
        if (text == null) {
            return null;
        }
        // Decimals as BigDecimal so currency values never pick up floating-point error.
        if (exactJson == null) {
            exactJson = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        }
        return exactJson.readValue(text, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    /** Removes trailing zeros without switching to exponent form (2500000.000000 → 2500000). */
    public static BigDecimal plain(BigDecimal n) {
        if (n == null) {
            return null;
        }
        BigDecimal s = n.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    public static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof List<?> l && l.isEmpty());
    }

    private static Object fail(Map<String, String> errors, String key, String message) {
        errors.put(key, message);
        return null;
    }

    public static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof Number y) {
            return x.compareTo(new BigDecimal(y.toString())) == 0;
        }
        return Objects.equals(a, b);
    }

    private static void checkLimits(Map<String, String> errors, DataType type, Integer maxLength, Integer decimals) {
        if (type == null) {
            return;
        }
        if (type.hasLength() && maxLength != null) {
            int limit = type == DataType.TEXT ? DEFAULT_TEXT_LENGTH : 131072;
            if (maxLength < 1 || maxLength > limit) {
                errors.put("maxLength", "Maximum length must be between 1 and " + limit + ".");
            }
        }
        if (type.isNumeric() && decimals != null && (decimals < 0 || decimals > 6)) {
            errors.put("decimalPlaces", "Decimal places must be between 0 and 6.");
        }
    }

    private static void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private String resolveSection(Map<String, String> errors, String object, String requested, boolean creating, String current) {
        if (Formats.isBlank(requested)) {
            if (!creating) {
                return current;
            }
            return jdbc.sql("SELECT name FROM custom_field_section WHERE object_api_name = :obj ORDER BY sort_order, name LIMIT 1")
                    .param("obj", object)
                    .query(String.class)
                    .optional()
                    .orElse("Personal Information");
        }
        String canonical = jdbc.sql("""
                        SELECT name FROM custom_field_section
                        WHERE object_api_name = :obj AND lower(btrim(name)) = lower(btrim(:sec))
                        LIMIT 1""")
                .param("obj", object)
                .param("sec", requested)
                .query(String.class)
                .optional()
                .orElse(null);
        if (canonical == null) {
            errors.put("section", "Select an existing section, or create the section first.");
            return creating ? "Personal Information" : current;
        }
        return canonical;
    }

    static String recordKey(String object) {
        String key = object == null ? null : object.trim().toLowerCase();
        recordTable(key);
        return key;
    }

    private static String recordTable(String object) {
        String table = object == null ? null : RECORD_TABLE.get(object);
        if (table == null) {
            throw new NotFoundException("Record type", object);
        }
        return table;
    }

    /** "Preferred contact time" → preferred_contact_time__c, made unique within the record type. */
    private String uniqueApiName(String object, String label) {
        String base = label.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "field_" + base;
        }
        base = base.length() > 60 ? base.substring(0, 60).replaceAll("_+$", "") : base;
        String candidate = base + "__c";
        for (int i = 2; apiNameExists(object, candidate); i++) {
            candidate = base + "_" + i + "__c";
        }
        return candidate;
    }

    private boolean apiNameExists(String object, String apiName) {
        return jdbc.sql("SELECT count(*) FROM custom_field_def WHERE object_api_name = :obj AND api_name = :api")
                .param("obj", object).param("api", apiName).query(Long.class).single() > 0;
    }

    /** Each dropdown custom field gets its own option list, managed in Setup like the built-in lists. */
    private String createPicklist(String object, String apiName, String label, boolean multi, List<String> values) {
        String base = ("CF_" + object + "_" + apiName.replace("__c", "")).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        base = base.length() > 55 ? base.substring(0, 55) : base;
        String code = base;
        for (int i = 2; jdbc.sql("SELECT count(*) FROM picklist_set WHERE code = :c").param("c", code)
                .query(Long.class).single() > 0; i++) {
            code = base + "_" + i;
        }
        int order = jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM picklist_set").query(Integer.class).single();
        jdbc.sql("""
                        INSERT INTO picklist_set (code, name, description, multi_select, locked, placeholder, sort_order)
                        VALUES (:code, :name, :desc, :multi, FALSE, FALSE, :sort)""")
                .param("code", code).param("name", label)
                .param("desc", "Options for the custom field " + label + " on " + object + ".")
                .param("multi", multi).param("sort", order)
                .update();
        Set<String> usedCodes = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            String valueCode = values.get(i).toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
            valueCode = valueCode.isEmpty() ? "VALUE" : valueCode;
            String unique = valueCode;
            for (int k = 2; !usedCodes.add(unique); k++) {
                unique = valueCode + "_" + k;
            }
            jdbc.sql("""
                            INSERT INTO picklist_value (set_code, code, label, arm, active, system_value, sort_order)
                            VALUES (:set, :code, :label, 'BOTH', TRUE, FALSE, :sort)""")
                    .param("set", code).param("code", unique).param("label", values.get(i)).param("sort", i)
                    .update();
        }
        return code;
    }
}
