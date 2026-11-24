package com.privateinvest.crm.record;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldService;

/**
 * Display conditions and formulas of configurable fields. The browser applies the same rules (fields/logic.ts),
 * so a field shows, hides and becomes required the same way on screen and on the server.
 */
public final class FieldLogic {

    private FieldLogic() {
    }

    /** Whether a field shows: every visibleWhen condition holds (no conditions: always). */
    public static boolean visible(CustomFieldDef d, Map<String, Object> values, Map<String, Object> context) {
        return all(d.displayRules() == null ? null : d.displayRules().get("visibleWhen"), values, context);
    }

    /** Whether a field must have a value: it is mandatory, or it shows and every requiredWhen condition holds. */
    public static boolean requiredNow(CustomFieldDef d, Map<String, Object> values, Map<String, Object> context) {
        Object when = d.displayRules() == null ? null : d.displayRules().get("requiredWhen");
        if (!(when instanceof List<?> l) || l.isEmpty()) {
            return false;
        }
        return visible(d, values, context) && all(when, values, context);
    }

    private static boolean all(Object conditions, Map<String, Object> values, Map<String, Object> context) {
        if (!(conditions instanceof List<?> list)) {
            return true;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> c && !holds(c, values, context)) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(Map<?, ?> c, Map<String, Object> values, Map<String, Object> context) {
        String field = String.valueOf(c.get("field"));
        Object actual = field.startsWith("$") ? context.get(field.substring(1)) : values.get(field);
        Object expected = c.get("value");
        String op = String.valueOf(c.get("operator"));
        return switch (op) {
            case "IS_EMPTY" -> CustomFieldService.isEmpty(actual);
            case "IS_NOT_EMPTY" -> !CustomFieldService.isEmpty(actual);
            case "IS_TRUE" -> Boolean.TRUE.equals(actual);
            case "IS_FALSE" -> !Boolean.TRUE.equals(actual);
            case "EQUALS" -> matchesAny(actual, List.of(expected));
            case "NOT_EQUALS" -> !matchesAny(actual, List.of(expected));
            case "IN", "CONTAINS_ANY" -> matchesAny(actual, expected instanceof Collection<?> e ? e : List.of(expected));
            case "NOT_IN" -> !matchesAny(actual, expected instanceof Collection<?> e ? e : List.of(expected));
            default -> false;
        };
    }

    /** A single value equal to one of the expected values, or a multi-value field holding one of them. */
    private static boolean matchesAny(Object actual, Collection<?> expected) {
        if (actual == null) {
            return false;
        }
        Collection<?> have = actual instanceof Collection<?> c ? c : List.of(actual);
        for (Object h : have) {
            for (Object e : expected) {
                if (Objects.equals(String.valueOf(h), String.valueOf(e))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the values of derived formula fields (WEEKS_SINCE, DAYS_SINCE a date field), worked out for
     * {@code today}. A date in the future, or no date, gives no value.
     */
    public static void derive(List<CustomFieldDef> defs, Map<String, Object> values, LocalDate today) {
        for (CustomFieldDef d : defs) {
            if (!d.active() || !d.derived()) {
                continue;
            }
            Object source = values.get(String.valueOf(d.formula().get("field")));
            values.remove(d.apiName());
            if (source == null) {
                continue;
            }
            LocalDate from;
            try {
                from = LocalDate.parse(String.valueOf(source));
            } catch (DateTimeParseException ex) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(from, today);
            if (days < 0) {
                continue;
            }
            switch (String.valueOf(d.formula().get("fn"))) {
                case "WEEKS_SINCE" -> values.put(d.apiName(), BigDecimal.valueOf(days / 7));
                case "DAYS_SINCE" -> values.put(d.apiName(), BigDecimal.valueOf(days));
                default -> {
                }
            }
        }
    }
}
