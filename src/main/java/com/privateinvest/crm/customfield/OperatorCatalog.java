package com.privateinvest.crm.customfield;

import java.util.List;
import java.util.Map;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;

/** Filter operators each field type supports. Sent with the field definitions and enforced by the search. */
public final class OperatorCatalog {

    public enum Operator {
        EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, STARTS_WITH, GT, GTE, LT, LTE, BETWEEN, BEFORE, AFTER, IN, NOT_IN,
        CONTAINS_ANY, CONTAINS_ALL, EXCLUDES, IS_TRUE, IS_FALSE, IS_ME, IS_EMPTY, IS_NOT_EMPTY;

        /** Operators that take no value. */
        public boolean noValue() {
            return this == IS_EMPTY || this == IS_NOT_EMPTY || this == IS_TRUE || this == IS_FALSE || this == IS_ME;
        }
    }

    /** The operators Salesforce list views offer, per type: equals, not equal to, contains, does not contain, ... */
    private static final List<Operator> TEXT = List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.CONTAINS,
            Operator.NOT_CONTAINS, Operator.STARTS_WITH, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);
    private static final Map<DataType, List<Operator>> BY_TYPE = Map.ofEntries(
            Map.entry(DataType.TEXT, TEXT),
            Map.entry(DataType.LONG_TEXT, TEXT),
            Map.entry(DataType.URL, TEXT),
            Map.entry(DataType.EMAIL, TEXT),
            Map.entry(DataType.PHONE, List.of(Operator.EQUALS, Operator.CONTAINS, Operator.NOT_CONTAINS, Operator.IS_EMPTY,
                    Operator.IS_NOT_EMPTY)),
            Map.entry(DataType.NUMBER, numeric()),
            Map.entry(DataType.CURRENCY, numeric()),
            Map.entry(DataType.PERCENT, numeric()),
            Map.entry(DataType.DATE, List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.BEFORE, Operator.AFTER,
                    Operator.LTE, Operator.GTE, Operator.BETWEEN, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY)),
            Map.entry(DataType.PICKLIST, List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN, Operator.NOT_IN,
                    Operator.IS_EMPTY, Operator.IS_NOT_EMPTY)),
            Map.entry(DataType.MULTI_PICKLIST, List.of(Operator.CONTAINS_ANY, Operator.CONTAINS_ALL, Operator.EXCLUDES,
                    Operator.IS_EMPTY, Operator.IS_NOT_EMPTY)),
            Map.entry(DataType.BOOLEAN, List.of(Operator.IS_TRUE, Operator.IS_FALSE)),
            Map.entry(DataType.CHECKBOX, List.of(Operator.IS_TRUE, Operator.IS_FALSE)),
            Map.entry(DataType.USER, List.of(Operator.IN, Operator.NOT_IN, Operator.IS_ME)),
            // Lists match when any of their items does.
            Map.entry(DataType.PHONE_LIST, List.of(Operator.EQUALS, Operator.CONTAINS, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY)),
            Map.entry(DataType.EMAIL_LIST, List.of(Operator.EQUALS, Operator.CONTAINS, Operator.STARTS_WITH, Operator.IS_EMPTY,
                    Operator.IS_NOT_EMPTY)));

    private OperatorCatalog() {
    }

    private static List<Operator> numeric() {
        return List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.GT, Operator.GTE, Operator.LT, Operator.LTE,
                Operator.BETWEEN, Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);
    }

    public static List<Operator> forType(DataType type) {
        return BY_TYPE.getOrDefault(type, List.of());
    }

    public static boolean supports(DataType type, Operator op) {
        return forType(type).contains(op);
    }
}
