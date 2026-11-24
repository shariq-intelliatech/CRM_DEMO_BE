package com.privateinvest.crm.record.search;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldDtos.Storage;
import com.privateinvest.crm.customfield.OperatorCatalog;
import com.privateinvest.crm.customfield.OperatorCatalog.Operator;
import com.privateinvest.crm.record.search.SearchDtos.FilterNode;
import com.privateinvest.crm.record.search.SearchDtos.SortKey;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the WHERE and ORDER BY of a record search (any record type, table aliased {@code p}) from field
 * definitions. Safe by construction:
 * <ul>
 *   <li>Field names must be enabled fields of the registry (filterable / sortable, visible to the user).</li>
 *   <li>Every field is in the custom_fields JSONB column; field keys and every
 *       value are bound parameters. No text from a request or an admin is ever written into the SQL.</li>
 *   <li>Values are converted to the field's type before binding; LIKE wildcards are escaped.</li>
 * </ul>
 * One builder serves one query (it collects that query's parameters).
 */
final class RecordQueryBuilder {

    static final int MAX_CONDITIONS = 20;
    static final int MAX_DEPTH = 3;
    static final int MAX_SORT = 3;
    static final int MAX_Q = 100;
    private static final int MAX_LIST = 100;
    private static final int MAX_TEXT = 200;
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final java.util.regex.Pattern RELATIVE_N = java.util.regex.Pattern.compile("^(LAST|NEXT)_N_DAYS:(\\d{1,4})$");
    private static final java.util.regex.Pattern AU_DATE = java.util.regex.Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

    private final Map<String, CustomFieldDef> fields;
    private final boolean canSensitive;
    private final String currentUserId;
    private final JsonMapper json;
    /** "Today" in the CRM's time zone, for relative dates. */
    private final LocalDate today;
    /** The record-name fields, searched together so "Olivia Chen" matches across first and last name. */
    private final List<String> nameFields;
    /** "Person" or "Entity", for messages. */
    private final String singular;
    private final Map<String, Object> params = new HashMap<>();
    private int counter;
    private int conditionCount;

    RecordQueryBuilder(List<CustomFieldDef> registry, boolean canSensitive, String currentUserId, JsonMapper json,
                       LocalDate today, List<String> nameFields, String singular) {
        this.singular = singular;
        this.today = today;
        this.nameFields = nameFields;
        this.fields = new HashMap<>();
        registry.stream().filter(CustomFieldDef::active).forEach(d -> fields.put(d.apiName(), d));
        this.canSensitive = canSensitive;
        this.currentUserId = currentUserId;
        this.json = json;
    }

    Map<String, Object> params() {
        return params;
    }

    /* ---------- WHERE ---------- */

    /**
     * Quick search: OR across fields marked searchable (text, numbers, dates and dropdown labels of custom fields
     * included) and the labels and values of this record's one-off fields. Null when {@code q} is blank.
     */
    String quickSearch(String q) {
        if (Formats.isBlank(q)) {
            return null;
        }
        String needle = q.trim();
        if (needle.length() > MAX_Q) {
            throw invalid("q", "Search for at most " + MAX_Q + " characters.");
        }
        String like = param("%" + escapeLike(needle) + "%");
        String digits = Formats.digits(needle);
        String digitsLike = digits.length() >= 3 ? param("%" + digits + "%") : null;
        // "$250,000" or "12.5%" matches a stored 250000 or 12.5.
        String number = needle.replaceAll("[\\s,$%]", "");
        String numberLike = NUMBER.matcher(number).matches() ? param("%" + number + "%") : null;
        // An Australian date typed as 15/01/2027 matches a stored 2027-01-15.
        String isoDate = isoDate(needle);
        String dateLike = isoDate != null ? param("%" + isoDate + "%") : null;
        List<String> ors = new ArrayList<>();
        for (CustomFieldDef d : fields.values()) {
            if (d.searchable() && (!d.sensitive() || canSensitive) && d.storage() == Storage.CUSTOM && !d.derived()) {
                customQuickSearch(d, ors, like, digitsLike, numberLike, dateLike);
            }
        }
        if (nameFields.size() > 1) {
            List<String> parts = nameFields.stream().map(f -> "p.custom_fields ->> " + param(f)).toList();
            ors.add("concat_ws(' ', " + String.join(", ", parts) + ") ILIKE " + like + " ESCAPE '\\'");
        }
        // Fields for this record only: a match on the field's label or its value.
        List<String> recordMatch = new ArrayList<>();
        recordMatch.add("rf.value ->> 'label' ILIKE " + like + " ESCAPE '\\'");
        recordMatch.add("rf.value ->> 'value' ILIKE " + like + " ESCAPE '\\'");
        if (numberLike != null) {
            recordMatch.add("(rf.value ->> 'dataType' IN ('NUMBER', 'CURRENCY', 'PERCENT') AND rf.value ->> 'value' LIKE " + numberLike + ")");
        }
        if (digitsLike != null) {
            recordMatch.add("(rf.value ->> 'dataType' = 'PHONE' AND regexp_replace(rf.value ->> 'value', '\\D', '', 'g') LIKE " + digitsLike + ")");
        }
        if (dateLike != null) {
            recordMatch.add("(rf.value ->> 'dataType' = 'DATE' AND rf.value ->> 'value' LIKE " + dateLike + ")");
        }
        ors.add("EXISTS (SELECT 1 FROM jsonb_each(p.record_fields) rf WHERE rf.value ->> 'removedAt' IS NULL AND ("
                + String.join(" OR ", recordMatch) + "))");
        return "(" + String.join(" OR ", ors) + ")";
    }

    /** Quick-search conditions for one shared custom field, by its type. Yes/No fields aren't searched by text. */
    private void customQuickSearch(CustomFieldDef d, List<String> ors, String like, String digitsLike, String numberLike,
                                   String dateLike) {
        String raw = custom(d);
        switch (d.dataType()) {
            // A list matches when any of its items does.
            case PHONE_LIST -> {
                if (digitsLike != null) {
                    ors.add(anyItem(d, "regexp_replace(it ->> 'value', '\\D', '', 'g') LIKE " + digitsLike));
                }
            }
            case EMAIL_LIST -> ors.add(anyItem(d, "it ->> 'value' ILIKE " + like + " ESCAPE '\\'"));
            case PHONE -> {
                if (digitsLike != null) {
                    ors.add("regexp_replace(" + raw + ", '\\D', '', 'g') LIKE " + digitsLike);
                }
            }
            case TEXT, LONG_TEXT, EMAIL, URL -> ors.add(raw + " ILIKE " + like + " ESCAPE '\\'");
            case NUMBER, CURRENCY, PERCENT -> {
                if (numberLike != null) {
                    ors.add(raw + " LIKE " + numberLike);
                }
            }
            case DATE -> {
                ors.add(raw + " LIKE " + like + " ESCAPE '\\'");
                if (dateLike != null) {
                    ors.add(raw + " LIKE " + dateLike);
                }
            }
            // Dropdowns match on the option's label, as users see it, not its stored code.
            case PICKLIST -> ors.add("EXISTS (SELECT 1 FROM picklist_value pv WHERE pv.set_code = " + param(d.picklist())
                    + " AND pv.code = " + raw + " AND pv.label ILIKE " + like + " ESCAPE '\\')");
            case MULTI_PICKLIST -> ors.add("EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.custom_fields -> "
                    + param(d.apiName()) + ") sel(code) JOIN picklist_value pv ON pv.set_code = " + param(d.picklist())
                    + " AND pv.code = sel.code WHERE pv.label ILIKE " + like + " ESCAPE '\\')");
            default -> {
            }
        }
    }

    /** "15/01/2027" → "2027-01-15"; null when the text isn't a d/m/yyyy date. */
    private static String isoDate(String s) {
        java.util.regex.Matcher m = AU_DATE.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))).toString();
        } catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    /** The filter tree as SQL, or null when there is no filter. */
    String filter(FilterNode node) {
        if (node == null) {
            return null;
        }
        return node(node, 1);
    }

    private String node(FilterNode n, int depth) {
        if (n.isGroup()) {
            if (depth > MAX_DEPTH) {
                throw invalid("filter", "Filters can be nested at most " + MAX_DEPTH + " levels deep.");
            }
            String op = n.op() == null ? "AND" : n.op().trim().toUpperCase();
            if (op.equals("NOT")) {
                if (n.conditions().size() != 1) {
                    throw invalid("filter", "NOT applies to one condition or group.");
                }
                String inner = node(n.conditions().getFirst(), depth + 1);
                // A row where the condition is unknown (e.g. an empty field) counts as "not matching", so NOT keeps it.
                return inner == null ? null : "NOT COALESCE(" + inner + ", FALSE)";
            }
            if (!op.equals("AND") && !op.equals("OR")) {
                throw invalid("filter", "Combine conditions with AND or OR.");
            }
            List<String> parts = new ArrayList<>();
            for (FilterNode c : n.conditions()) {
                String sql = node(c, depth + 1);
                if (sql != null) {
                    parts.add(sql);
                }
            }
            return parts.isEmpty() ? null : "(" + String.join(" " + op + " ", parts) + ")";
        }
        if (++conditionCount > MAX_CONDITIONS) {
            throw invalid("filter", "Use at most " + MAX_CONDITIONS + " conditions.");
        }
        CustomFieldDef d = field(n.field(), "filter");
        if (!d.filterable()) {
            throw invalid("filter", "'" + d.label() + "' can't be used in a filter.");
        }
        Operator op;
        try {
            op = Operator.valueOf(String.valueOf(n.operator()).trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw invalid("filter", "Unknown operator '" + n.operator() + "'.");
        }
        if (!OperatorCatalog.supports(d.dataType(), op)) {
            throw unsupported(d, op);
        }
        return "(" + condition(d, op, n.value()) + ")";
    }

    private String condition(CustomFieldDef d, Operator op, Object value) {
        if (op == Operator.IS_EMPTY || op == Operator.IS_NOT_EMPTY) {
            return emptiness(d, op);
        }
        String raw = custom(d);
        DataType t = d.dataType();
        if (t == DataType.PHONE_LIST) {
            String digits = "regexp_replace(it ->> 'value', '\\D', '', 'g')";
            return switch (op) {
                case EQUALS -> anyItem(d, digits + " = " + param(phoneDigits(value)));
                case CONTAINS -> anyItem(d, digits + " LIKE " + param("%" + phoneDigits(value) + "%"));
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.EMAIL_LIST) {
            String lower = "lower(it ->> 'value')";
            return switch (op) {
                case EQUALS -> anyItem(d, lower + " IN (" + param(lowerList(value)) + ")");
                case CONTAINS -> anyItem(d, anyLike(lower, value, true));
                case STARTS_WITH -> anyItem(d, anyLike(lower, value, false));
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.PHONE) {
            String digits = "regexp_replace(" + raw + ", '\\D', '', 'g')";
            return switch (op) {
                case EQUALS -> digits + " = " + param(phoneDigits(value));
                case CONTAINS -> digits + " LIKE " + param("%" + phoneDigits(value) + "%");
                case NOT_CONTAINS -> raw + " IS NULL OR " + digits + " NOT LIKE " + param("%" + phoneDigits(value) + "%");
                default -> throw unsupported(d, op);
            };
        }
        if (t.isText()) {
            String lower = "lower(" + raw + ")";
            return switch (op) {
                // "equals A, B" (a list) matches any of the values, as in Salesforce.
                case EQUALS -> lower + " IN (" + param(lowerList(value)) + ")";
                case NOT_EQUALS -> raw + " IS NULL OR " + lower + " NOT IN (" + param(lowerList(value)) + ")";
                case CONTAINS -> anyLike(lower, value, true);
                case NOT_CONTAINS -> raw + " IS NULL OR NOT (" + anyLike(lower, value, true) + ")";
                case STARTS_WITH -> anyLike(lower, value, false);
                default -> throw unsupported(d, op);
            };
        }
        if (t.isNumeric()) {
            String e = "(" + raw + ")::numeric";
            return switch (op) {
                case EQUALS -> e + " IN (" + param(numbers(value)) + ")";
                case NOT_EQUALS -> e + " IS NULL OR " + e + " NOT IN (" + param(numbers(value)) + ")";
                case GT -> e + " > " + param(number(value));
                case GTE -> e + " >= " + param(number(value));
                case LT -> e + " < " + param(number(value));
                case LTE -> e + " <= " + param(number(value));
                case BETWEEN -> {
                    List<?> range = pair(value);
                    BigDecimal lo = number(range.get(0));
                    BigDecimal hi = number(range.get(1));
                    if (lo.compareTo(hi) > 0) {
                        throw invalid("filter", "The 'from' value can't be more than the 'to' value.");
                    }
                    yield e + " BETWEEN " + param(lo) + " AND " + param(hi);
                }
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.DATE) {
            String e = "(" + raw + ")::date";
            if (op == Operator.BETWEEN) {
                List<?> range = pair(value);
                LocalDate lo = dateRange(range.get(0))[0];
                LocalDate hi = dateRange(range.get(1))[1];
                if (lo.isAfter(hi)) {
                    throw invalid("filter", "The 'from' date can't be after the 'to' date.");
                }
                return e + " BETWEEN " + param(lo) + " AND " + param(hi);
            }
            // A relative date ("THIS_MONTH", "LAST_N_DAYS:30") is a range; a fixed date is a range of one day.
            LocalDate[] r = dateRange(value);
            return switch (op) {
                case EQUALS -> e + " BETWEEN " + param(r[0]) + " AND " + param(r[1]);
                case NOT_EQUALS -> e + " IS NULL OR " + e + " NOT BETWEEN " + param(r[0]) + " AND " + param(r[1]);
                case BEFORE -> e + " < " + param(r[0]);
                case AFTER -> e + " > " + param(r[1]);
                case LTE -> e + " <= " + param(r[1]);
                case GTE -> e + " >= " + param(r[0]);
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.PICKLIST || t == DataType.USER) {
            return switch (op) {
                // One dropdown value on a custom field: JSON containment, served by the GIN index.
                case EQUALS, IN -> value instanceof String v ? contains(d, text(v)) : raw + " IN (" + param(list(value)) + ")";
                case NOT_EQUALS, NOT_IN -> raw + " IS NULL OR " + raw + " NOT IN (" + param(list(value)) + ")";
                case IS_ME -> raw + " = " + param(currentUserId);
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.MULTI_PICKLIST) {
            List<String> codes = list(value);
            // JSON containment (served by the GIN index): an array contains every listed code.
            if (op == Operator.CONTAINS_ALL) {
                return "p.custom_fields @> CAST(" + param(doc(d.apiName(), codes)) + " AS jsonb)";
            }
            String any = String.join(" OR ", codes.stream()
                    .map(c -> "p.custom_fields @> CAST(" + param(doc(d.apiName(), List.of(c))) + " AS jsonb)").toList());
            return switch (op) {
                case CONTAINS_ANY -> any;
                case EXCLUDES -> "NOT (" + any + ")";
                default -> throw unsupported(d, op);
            };
        }
        if (t == DataType.BOOLEAN || t == DataType.CHECKBOX) {
            String isTrue = "p.custom_fields @> CAST(" + param(doc(d.apiName(), true)) + " AS jsonb)";
            if (op == Operator.IS_TRUE) {
                return isTrue;
            }
            // Anything not true is false: an unticked checkbox and an unanswered Yes/No both count as "no".
            return "NOT " + isTrue;
        }
        throw invalid("filter", "'" + d.label() + "' can't be filtered.");
    }

    private String emptiness(CustomFieldDef d, Operator op) {
        String empty = "(p.custom_fields -> " + param(d.apiName()) + ") IS NULL";
        return op == Operator.IS_EMPTY ? empty : "NOT (" + empty + ")";
    }

    /** One or more LIKE patterns OR-ed together: "contains A, B" matches either. */
    private String anyLike(String expr, Object value, boolean anywhere) {
        return String.join(" OR ", lowerList(value).stream()
                .map(v -> expr + " LIKE " + param((anywhere ? "%" : "") + escapeLike(v) + "%") + " ESCAPE '\\'")
                .toList());
    }

    private static FieldValidationException unsupported(CustomFieldDef d, Operator op) {
        return invalid("filter", "'" + op + "' can't be used with " + d.label() + ".");
    }

    /* ---------- Relative dates ---------- */

    /**
     * A fixed date ("2027-01-15") or a Salesforce-style relative date, as a [first, last] day range: TODAY,
     * YESTERDAY, TOMORROW, THIS/LAST/NEXT_WEEK (weeks start Monday), THIS/LAST/NEXT_MONTH, THIS/LAST/NEXT_QUARTER,
     * THIS/LAST/NEXT_YEAR, LAST_N_DAYS:n (today and the n days before), NEXT_N_DAYS:n (the n days after today).
     */
    LocalDate[] dateRange(Object value) {
        String s = text(value).toUpperCase();
        LocalDate d = today;
        java.util.regex.Matcher n = RELATIVE_N.matcher(s);
        if (n.matches()) {
            int days = Integer.parseInt(n.group(2));
            if (days < 1 || days > 3650) {
                throw invalid("filter", "Use between 1 and 3650 days.");
            }
            return n.group(1).equals("LAST") ? new LocalDate[] {d.minusDays(days), d}
                    : new LocalDate[] {d.plusDays(1), d.plusDays(days)};
        }
        LocalDate weekStart = d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate monthStart = d.withDayOfMonth(1);
        LocalDate quarterStart = d.withMonth((d.getMonthValue() - 1) / 3 * 3 + 1).withDayOfMonth(1);
        LocalDate yearStart = d.withDayOfYear(1);
        return switch (s) {
            case "TODAY" -> new LocalDate[] {d, d};
            case "YESTERDAY" -> new LocalDate[] {d.minusDays(1), d.minusDays(1)};
            case "TOMORROW" -> new LocalDate[] {d.plusDays(1), d.plusDays(1)};
            case "THIS_WEEK" -> new LocalDate[] {weekStart, weekStart.plusDays(6)};
            case "LAST_WEEK" -> new LocalDate[] {weekStart.minusWeeks(1), weekStart.minusDays(1)};
            case "NEXT_WEEK" -> new LocalDate[] {weekStart.plusWeeks(1), weekStart.plusWeeks(1).plusDays(6)};
            case "THIS_MONTH" -> new LocalDate[] {monthStart, monthStart.plusMonths(1).minusDays(1)};
            case "LAST_MONTH" -> new LocalDate[] {monthStart.minusMonths(1), monthStart.minusDays(1)};
            case "NEXT_MONTH" -> new LocalDate[] {monthStart.plusMonths(1), monthStart.plusMonths(2).minusDays(1)};
            case "THIS_QUARTER" -> new LocalDate[] {quarterStart, quarterStart.plusMonths(3).minusDays(1)};
            case "LAST_QUARTER" -> new LocalDate[] {quarterStart.minusMonths(3), quarterStart.minusDays(1)};
            case "NEXT_QUARTER" -> new LocalDate[] {quarterStart.plusMonths(3), quarterStart.plusMonths(6).minusDays(1)};
            case "THIS_YEAR" -> new LocalDate[] {yearStart, yearStart.plusYears(1).minusDays(1)};
            case "LAST_YEAR" -> new LocalDate[] {yearStart.minusYears(1), yearStart.minusDays(1)};
            case "NEXT_YEAR" -> new LocalDate[] {yearStart.plusYears(1), yearStart.plusYears(2).minusDays(1)};
            default -> {
                LocalDate fixed = date(value);
                yield new LocalDate[] {fixed, fixed};
            }
        };
    }

    /* ---------- ORDER BY ---------- */

    /** Sort keys as SQL; falls back to newest created first and always ends with id so pages stay stable. */
    String orderBy(List<SortKey> sort, List<SortKey> fallback) {
        List<SortKey> keys = sort == null || sort.isEmpty() ? fallback : sort;
        if (keys.size() > MAX_SORT) {
            throw invalid("sort", "Sort by at most " + MAX_SORT + " fields.");
        }
        List<String> parts = new ArrayList<>();
        for (SortKey k : keys) {
            CustomFieldDef d = field(k.field(), "sort");
            if (!d.sortable() || d.dataType() == DataType.MULTI_PICKLIST) {
                throw invalid("sort", "'" + d.label() + "' can't be sorted.");
            }
            String dir = k.dir() == null ? "ASC" : k.dir().trim().toUpperCase();
            if (!dir.equals("ASC") && !dir.equals("DESC")) {
                throw invalid("sort", "Sort direction is ASC or DESC.");
            }
            parts.add(sortExpression(d) + " " + dir + " NULLS LAST");
        }
        parts.add("p.created_at DESC");
        parts.add("p.id");
        return String.join(", ", parts);
    }

    private String sortExpression(CustomFieldDef d) {
        String raw = custom(d);
        DataType t = d.dataType();
        if (t.isList()) {
            // A list sorts by its primary item.
            return "lower((SELECT it ->> 'value' FROM jsonb_array_elements(" + listDoc(d) + ") it "
                    + "WHERE (it ->> 'primary')::boolean LIMIT 1))";
        }
        if (t == DataType.PICKLIST) {
            return "(SELECT pv.label FROM picklist_value pv WHERE pv.set_code = " + param(d.picklist()) + " AND pv.code = " + raw + ")";
        }
        if (t == DataType.USER) {
            return "(SELECT u.name FROM app_user u WHERE u.id = " + raw + ")";
        }
        if (t.isNumeric()) {
            return "(" + raw + ")::numeric";
        }
        if (t == DataType.DATE) {
            return "(" + raw + ")::date";
        }
        if (t == DataType.BOOLEAN || t == DataType.CHECKBOX) {
            return "(" + raw + ")::boolean";
        }
        return "lower(" + raw + ")";
    }

    /* ---------- Helpers ---------- */

    CustomFieldDef field(String apiName, String where) {
        CustomFieldDef d = apiName == null ? null : fields.get(apiName);
        if (d == null || (d.sensitive() && !canSensitive) || d.storage() != Storage.CUSTOM) {
            throw invalid(where, "There is no " + singular + " field called '" + apiName + "'.");
        }
        if (d.derived() && !where.equals("columns")) {
            throw invalid(where, "'" + d.label() + "' is worked out when it is shown, so it can't be used to " + where + ".");
        }
        return d;
    }

    /** The field's array, or no rows when the field is unset; the key is a bound parameter. */
    private String listDoc(CustomFieldDef d) {
        String k = param(d.apiName());
        return "CASE WHEN jsonb_typeof(p.custom_fields -> " + k + ") = 'array' THEN p.custom_fields -> " + k + " END";
    }

    /** True when any item of a phone or email list matches {@code condition} (written against alias "it"). */
    private String anyItem(CustomFieldDef d, String condition) {
        return "EXISTS (SELECT 1 FROM jsonb_array_elements(" + listDoc(d) + ") it WHERE " + condition + ")";
    }

    /** A CUSTOM value as text; the key is a bound parameter. */
    private String custom(CustomFieldDef d) {
        return "(p.custom_fields ->> " + param(d.apiName()) + ")";
    }

    private String contains(CustomFieldDef d, Object value) {
        return "p.custom_fields @> CAST(" + param(doc(d.apiName(), value)) + " AS jsonb)";
    }

    private String doc(String key, Object value) {
        return json.writeValueAsString(Map.of(key, value));
    }

    String param(Object value) {
        String name = "p" + counter++;
        params.put(name, value);
        return ":" + name;
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw invalid("filter", "Enter a value to filter by.");
        }
        if (s.length() > MAX_TEXT) {
            throw invalid("filter", "Filter values can be at most " + MAX_TEXT + " characters.");
        }
        return s.trim();
    }

    private static String phoneDigits(Object value) {
        String digits = Formats.digits(text(value));
        if (digits.isEmpty()) {
            throw invalid("filter", "Enter some digits of the phone number.");
        }
        return digits;
    }

    private static BigDecimal number(Object value) {
        try {
            return value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(text(value).replace(",", ""));
        } catch (NumberFormatException ex) {
            throw invalid("filter", "Enter a number.");
        }
    }

    private static LocalDate date(Object value) {
        try {
            return LocalDate.parse(text(value));
        } catch (DateTimeParseException ex) {
            throw invalid("filter", "Enter a date as YYYY-MM-DD.");
        }
    }

    private static List<?> pair(Object value) {
        if (!(value instanceof List<?> l) || l.size() != 2) {
            throw invalid("filter", "Between needs a 'from' and a 'to' value.");
        }
        return l;
    }

    /** A value or a list of values, as lower-case text. */
    private static List<String> lowerList(Object value) {
        return (value instanceof List<?> ? list(value) : List.of(text(value))).stream().map(String::toLowerCase).toList();
    }

    private static List<BigDecimal> numbers(Object value) {
        return value instanceof List<?> l
                ? list(l.stream().map(String::valueOf).toList()).stream().map(RecordQueryBuilder::number).toList()
                : List.of(number(value));
    }

    private static List<String> list(Object value) {
        List<?> raw = value instanceof List<?> l ? l : value instanceof String s ? List.of(s) : null;
        if (raw == null || raw.isEmpty() || raw.size() > MAX_LIST || !raw.stream().allMatch(v -> v instanceof String s && !s.isBlank())) {
            throw invalid("filter", "Choose between 1 and " + MAX_LIST + " values.");
        }
        return raw.stream().map(v -> ((String) v).trim()).toList();
    }

    static FieldValidationException invalid(String key, String message) {
        return new FieldValidationException(Map.of(key, message));
    }
}
