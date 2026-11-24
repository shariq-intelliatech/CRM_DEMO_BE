package com.privateinvest.crm.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests of the display-condition engine behind the field map's conditional rows. The browser has the same
 * rules (FE src/features/records/fields/logic.ts, tested by logic.test.ts with the same cases), so a field
 * shows, hides and becomes required the same way on screen and on the server.
 */
@DisplayName("FieldLogic: display conditions and formulas")
class FieldLogicTest {

    /** A definition with only the parts FieldLogic reads; every other component takes its type's default. */
    static CustomFieldDef def(String apiName, Map<String, Object> displayRules, Map<String, Object> formula) {
        RecordComponent[] parts = CustomFieldDef.class.getRecordComponents();
        Object[] args = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            types[i] = parts[i].getType();
            args[i] = switch (parts[i].getName()) {
                case "apiName" -> apiName;
                case "label" -> apiName;
                case "active" -> true;
                case "displayRules" -> displayRules;
                case "formula" -> formula;
                default -> defaultOf(types[i]);
            };
        }
        try {
            return CustomFieldDef.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        return null;
    }

    private static Map<String, Object> cond(String field, String operator, Object value) {
        Map<String, Object> c = new HashMap<>();
        c.put("field", field);
        c.put("operator", operator);
        if (value != null) {
            c.put("value", value);
        }
        return c;
    }

    private static CustomFieldDef visibleAndRequiredWhen(Map<String, Object> condition) {
        return def("target", Map.of("visibleWhen", List.of(condition), "requiredWhen", List.of(condition)), null);
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("field map rules")
    class FieldMapRules {

        /** ABN: Required (if Yes) — soleTrader EQUALS YES. */
        private final CustomFieldDef abn = visibleAndRequiredWhen(cond("soleTrader", "EQUALS", "YES"));

        @ParameterizedTest(name = "Sole trader = {0} → ABN shown and required: {1}")
        @CsvSource(nullValues = "null", value = {"YES, true", "NO, false", "null, false"})
        void abnFollowsSoleTrader(String soleTrader, boolean expected) {
            Map<String, Object> v = values("soleTrader", soleTrader);
            assertThat(FieldLogic.visible(abn, v, Map.of())).isEqualTo(expected);
            assertThat(FieldLogic.requiredNow(abn, v, Map.of())).isEqualTo(expected);
        }

        @Test
        @DisplayName("Preferred phone: required when Preferred channel holds SMS or Phone")
        void preferredPhone() {
            CustomFieldDef phone = visibleAndRequiredWhen(cond("preferredChannels", "CONTAINS_ANY", List.of("SMS", "PHONE")));
            assertThat(FieldLogic.requiredNow(phone, values("preferredChannels", List.of("SMS")), Map.of())).isTrue();
            assertThat(FieldLogic.requiredNow(phone, values("preferredChannels", List.of("EMAIL", "PHONE")), Map.of())).isTrue();
            assertThat(FieldLogic.requiredNow(phone, values("preferredChannels", List.of("EMAIL")), Map.of())).isFalse();
            assertThat(FieldLogic.requiredNow(phone, values("preferredChannels", List.of()), Map.of())).isFalse();
            assertThat(FieldLogic.requiredNow(phone, values(), Map.of())).isFalse();
        }

        @ParameterizedTest(name = "Marketing consent = {0} → channels required: {1}")
        @CsvSource(nullValues = "null", value = {"OPTED_IN, true", "OPTED_OUT, true", "null, false", "'', false"})
        void marketingChannelsRegardlessOfConsent(String consent, boolean expected) {
            CustomFieldDef channels = visibleAndRequiredWhen(cond("marketingConsent", "IS_NOT_EMPTY", null));
            assertThat(FieldLogic.requiredNow(channels, values("marketingConsent", consent), Map.of())).isEqualTo(expected);
        }

        @Test
        @DisplayName("ACR / ACL / Industry Body: shown for a Broker linked to an Aggregator or Aggregator Office")
        void brokerProfile() {
            CustomFieldDef acr = def("acr", Map.of("visibleWhen", List.of(
                    cond("personTypes", "CONTAINS_ANY", List.of("BROKER")),
                    cond("$linkedEntityType", "IN", List.of("AGGREGATOR_GROUP", "AGGREGATOR_OFFICE")))), null);
            Map<String, Object> broker = values("personTypes", List.of("INVESTOR", "BROKER"));
            assertThat(FieldLogic.visible(acr, broker, Map.of("linkedEntityType", List.of("AGGREGATOR_OFFICE")))).isTrue();
            assertThat(FieldLogic.visible(acr, broker, Map.of("linkedEntityType", List.of("DEVELOPER", "AGGREGATOR_GROUP")))).isTrue();
            assertThat(FieldLogic.visible(acr, broker, Map.of("linkedEntityType", List.of("DEVELOPER")))).isFalse();
            assertThat(FieldLogic.visible(acr, broker, Map.of())).isFalse();
            assertThat(FieldLogic.visible(acr, values("personTypes", List.of("INVESTOR")),
                    Map.of("linkedEntityType", List.of("AGGREGATOR_GROUP")))).isFalse();
            // Visibility alone never makes a field required.
            assertThat(FieldLogic.requiredNow(acr, broker, Map.of("linkedEntityType", List.of("AGGREGATOR_GROUP")))).isFalse();
        }
    }

    @Nested
    @DisplayName("operators")
    class Operators {

        @ParameterizedTest(name = "{0} {1} {2} on {3} → {4}")
        @CsvSource(delimiter = '|', nullValues = "null", value = {
                "EQUALS     | x | A    | A      | true",
                "EQUALS     | x | A    | B      | false",
                "EQUALS     | x | A    | null   | false",
                "NOT_EQUALS | x | A    | B      | true",
                "NOT_EQUALS | x | A    | A      | false",
                "NOT_EQUALS | x | A    | null   | true",
                "IN         | x | A,B  | B      | true",
                "IN         | x | A,B  | C      | false",
                "NOT_IN     | x | A,B  | C      | true",
                "NOT_IN     | x | A,B  | A      | false",
                "IS_EMPTY   | x | null | null   | true",
                "IS_EMPTY   | x | null | ''     | true",
                "IS_EMPTY   | x | null | A      | false",
                "IS_NOT_EMPTY | x | null | A    | true",
                "IS_NOT_EMPTY | x | null | null | false",
                "UNKNOWN_OP | x | A    | A      | false"})
        void singleValues(String op, String field, String expected, String actual, boolean result) {
            Object value = expected == null ? null : expected.contains(",") ? List.of(expected.split(",")) : expected;
            CustomFieldDef d = def("t", Map.of("visibleWhen", List.of(cond(field, op, value))), null);
            assertThat(FieldLogic.visible(d, values(field, actual), Map.of())).isEqualTo(result);
        }

        @Test
        @DisplayName("IS_TRUE / IS_FALSE read only a real true")
        void booleans() {
            CustomFieldDef isTrue = def("t", Map.of("visibleWhen", List.of(cond("b", "IS_TRUE", null))), null);
            CustomFieldDef isFalse = def("t", Map.of("visibleWhen", List.of(cond("b", "IS_FALSE", null))), null);
            assertThat(FieldLogic.visible(isTrue, values("b", true), Map.of())).isTrue();
            assertThat(FieldLogic.visible(isTrue, values("b", "true"), Map.of())).isFalse();
            assertThat(FieldLogic.visible(isFalse, values("b", null), Map.of())).isTrue();
            assertThat(FieldLogic.visible(isFalse, values("b", false), Map.of())).isTrue();
        }

        @Test
        @DisplayName("an empty list is empty; values compare as text")
        void listsAndText() {
            CustomFieldDef empty = def("t", Map.of("visibleWhen", List.of(cond("m", "IS_EMPTY", null))), null);
            assertThat(FieldLogic.visible(empty, values("m", List.of()), Map.of())).isTrue();
            CustomFieldDef eq = def("t", Map.of("visibleWhen", List.of(cond("n", "EQUALS", "5"))), null);
            assertThat(FieldLogic.visible(eq, values("n", 5), Map.of())).isTrue();
        }

        @Test
        @DisplayName("no rules: always shown, never required by a condition")
        void noRules() {
            CustomFieldDef plain = def("t", Map.of(), null);
            assertThat(FieldLogic.visible(plain, values(), Map.of())).isTrue();
            assertThat(FieldLogic.requiredNow(plain, values(), Map.of())).isFalse();
            CustomFieldDef nullRules = def("t", null, null);
            assertThat(FieldLogic.visible(nullRules, values(), Map.of())).isTrue();
            assertThat(FieldLogic.requiredNow(nullRules, values(), Map.of())).isFalse();
        }

        @Test
        @DisplayName("a hidden field is never required, even when its requiredWhen holds")
        void hiddenIsNeverRequired() {
            CustomFieldDef d = def("t", Map.of(
                    "visibleWhen", List.of(cond("a", "EQUALS", "SHOW")),
                    "requiredWhen", List.of(cond("b", "EQUALS", "REQ"))), null);
            assertThat(FieldLogic.requiredNow(d, values("a", "HIDE", "b", "REQ"), Map.of())).isFalse();
            assertThat(FieldLogic.requiredNow(d, values("a", "SHOW", "b", "REQ"), Map.of())).isTrue();
            assertThat(FieldLogic.requiredNow(d, values("a", "SHOW", "b", "NO"), Map.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("formulas")
    class Formulas {

        private final LocalDate today = LocalDate.of(2026, 11, 23);

        @ParameterizedTest(name = "{0} days ago → {1} weeks")
        @CsvSource({"0, 0", "6, 0", "7, 1", "23, 3", "364, 52"})
        @DisplayName("LinkedIn Connect Age (Weeks) counts whole weeks since the connect date")
        void weeksSince(int daysAgo, int weeks) {
            CustomFieldDef age = def("linkedinAgeWeeks", Map.of(), Map.of("fn", "WEEKS_SINCE", "field", "linkedinDate"));
            Map<String, Object> v = values("linkedinDate", today.minusDays(daysAgo).toString());
            FieldLogic.derive(List.of(age), v, today);
            assertThat(v.get("linkedinAgeWeeks")).isEqualTo(BigDecimal.valueOf(weeks));
        }

        @Test
        @DisplayName("DAYS_SINCE counts days")
        void daysSince() {
            CustomFieldDef days = def("d", Map.of(), Map.of("fn", "DAYS_SINCE", "field", "since"));
            Map<String, Object> v = values("since", today.minusDays(10).toString());
            FieldLogic.derive(List.of(days), v, today);
            assertThat(v.get("d")).isEqualTo(BigDecimal.valueOf(10));
        }

        @Test
        @DisplayName("a future, missing or unreadable date gives no value, and a typed value is replaced")
        void noValueWithoutAGoodPastDate() {
            CustomFieldDef age = def("age", Map.of(), Map.of("fn", "WEEKS_SINCE", "field", "since"));
            for (Object source : new Object[] {today.plusDays(1).toString(), null, "not a date"}) {
                Map<String, Object> v = values("since", source, "age", BigDecimal.TEN);
                FieldLogic.derive(List.of(age), v, today);
                assertThat(v).as("source " + source).doesNotContainKey("age");
            }
        }
    }
}
