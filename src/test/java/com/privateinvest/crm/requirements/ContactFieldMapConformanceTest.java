package com.privateinvest.crm.requirements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.privateinvest.crm.ApiTestBase;
import com.privateinvest.crm.requirements.FieldMapCsv.Row;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Requirements traceability for the client field map (EXT_CRM_Field_Map_MASTERContact.csv). Every row of the
 * file is classified here as one of:
 * <ul>
 *   <li><b>built</b>: a Person field, checked against the row for label, data type, required rule, picklist
 *       values, business unit (arm) and record block (section);</li>
 *   <li><b>a documented deviation</b>: the build differs from the row on purpose (the brief or the design says
 *       otherwise). The test asserts the difference still exists, so the register never goes stale; when the
 *       build is changed to match the row the test fails and asks for the entry to be removed;</li>
 *   <li><b>not yet built</b>: reported as a skipped test with the reason, so the gap shows in every test report.</li>
 * </ul>
 * A row the test does not know (the client added or renamed one) fails {@link #everyFieldMapRowIsClassified()}.
 */
@DisplayName("Client field map (Contact) conformance")
class ContactFieldMapConformanceTest extends ApiTestBase {

    /* ------------------------------------------------------------------------------------------------------------
     * Classification of the rows
     * ---------------------------------------------------------------------------------------------------------- */

    /** Field map row (object | field) → the Person field that implements it. */
    private static final Map<String, String> BUILT = new LinkedHashMap<>();

    static {
        built("Contact", "Type of contact", "personTypes");
        built("Contact", "Type of investor", "investorSubType");
        built("Contact", "Type of service provider", "serviceProviderTypes");
        built("Contact", "First Name", "firstName");
        built("Contact", "Last Name", "lastName");
        built("Contact", "Preferred name", "preferredName");
        built("Contact", "Date of Birth", "dateOfBirth");
        built("Contact", "Address line 1", "street");
        built("Contact", "Address line 2", "addressLine2");
        built("Contact", "Suburb or city", "suburb");
        built("Contact", "State or region", "state");
        built("Contact", "Postcode", "postcode");
        built("Contact", "Country", "country");
        built("Contact", "Phones", "phones");
        built("Contact", "Email", "emails");
        built("Contact", "LinkedIn profile", "linkedinUrl");
        built("Contact", "Webpage", "webpage");
        built("Contact", "Sole trader", "soleTrader");
        built("Contact", "ABN", "abn");
        built("Contact", "Trading name", "tradingName");
        built("Contact", "Background", "background");
        built("Contact", "Contact Source", "contactSources");
        built("Contact", "Database source", "databaseSources");
        built("Contact", "Engagement style", "engagementStyle");
        built("Contact", "Preferred investment offerings", "preferredOfferings");
        built("Contact", "Preferred loan sector", "loanSectors");
        built("Contact", "Industry of wealth", "wealthIndustry");
        built("Contact", "Holder ID", "holderId");
        built("Contact", "Registration details", "registrationDetails");
        built("Contact", "Holder type", "holderType");
        built("Contact", "Preferred channel", "preferredChannels");
        built("Contact", "Preferred phone", "preferredPhone");
        built("Contact", "Preferred email", "preferredEmail");
        built("Contact", "Marketing consent", "marketingConsent");
        built("Contact", "Marketing channels", "marketingChannels");
        built("Contact", "Preferred marketing phone", "preferredMarketingPhone");
        built("Contact", "Preferred marketing email", "preferredMarketingEmail");
        built("Contact", "Date consent given", "consentGivenDate");
        built("Contact", "Date of withdrawal", "consentWithdrawnDate");
        built("Contact", "Last updated by", "marketingUpdatedBy");
        built("Contact", "LinkedIn First Connect Date", "linkedinDate");
        built("Contact", "LinkedIn Connection Status", "linkedinStatus");
        built("Contact", "LinkedIn Connect Age (Weeks)", "linkedinAgeWeeks");
        built("Contact", "Mailchimp Audience Added", "mailchimpAdded");
        built("Contact", "Mailchimp Audience Cleaned", "mailchimpCleaned");
        built("Contact", "Mailchimp Audience Non-Subscribed", "mailchimpNonSubscribed");
        built("Contact", "Mailchimp Audience Subscribed", "mailchimpSubscribed");
        built("Contact", "Mailchimp Audience Unsubscribed", "mailchimpUnsubscribed");
        built("Contact", "Engagement Temperature", "engagementTemperature");
        built("Contact", "Data quality", "dataQuality");
        built("Contact [Broker]", "ACR", "acr");
        built("Contact [Broker]", "ACL", "acl");
        built("Contact [Broker]", "Industry Body", "industryBody");
    }

    /** "Address" is the address form as a whole: the rows below it are its parts, each required. */
    private static final String ADDRESS_ROW = key("Contact", "Address");
    private static final List<String> ADDRESS_PARTS = List.of("street", "suburb", "state", "postcode", "country");

    /** Entity Role is the role on an Entity link: a picklist, not a Person field. */
    private static final String ENTITY_ROLE_ROW = key("Contact [Linked entities]", "Entity Role");
    private static final String ENTITY_ROLE_PICKLIST = "ENTITY_LINK_ROLE";

    /** Rows held by the Entity link itself (POST /api/entity-links), proved by EntityAndLinkApiTest. */
    private static final Map<String, String> ENTITY_LINK_ROWS = Map.of(
            key("Contact [Linked entities]", "Entity"), "The linked Entity of an Entity link.",
            key("Contact [Linked entities]", "Entity type"), "Read through the link (brief §3.4), never copied.",
            key("Contact [Linked entities]", "Date linked"), "Stamped by the CRM when the link is made.");

    /** Objects and rows the current build does not have yet, with where the brief schedules them. */
    private static final Map<String, String> NOT_BUILT_OBJECTS = Map.of(
            "Contact [Linked opportunities]", "Loan and Investment Opportunities are not built yet (brief §4, §5).",
            "Panel link [Broker to Aggregator]", "Panel membership is not built yet (brief §4.8).",
            "Note", "Notes (C1) are not built yet (brief §3.6.1).",
            "Activity", "Activity logging (C9) is not built yet (brief §3.6.4).");
    private static final Map<String, String> NOT_BUILT_ROWS = Map.of(
            key("Contact", "Document name"), "Documents block (SharePoint links) is not built yet (brief §3.9).",
            key("Contact", "Document link"), "Documents block (SharePoint links) is not built yet (brief §3.9).",
            key("Contact", "Added"), "Documents block (SharePoint links) is not built yet (brief §3.9).");

    /** Where the build differs from a row on purpose. Each needs the client's sign-off before go-live. */
    private enum Aspect { REQUIRED, TYPE, SECTION }

    private record Deviation(String row, Aspect aspect, String reason) {
    }

    // DEV-01 (Address line 2 Required) and DEV-03 (Contact Source, Database source and Engagement Temperature
    // blocks) were resolved to follow the field map by migration 2026-11-23_008_field_map_alignment.sql.
    private static final List<Deviation> DEVIATIONS = List.of(
            new Deviation(key("Contact", "Date of Birth"), Aspect.TYPE,
                    "The map types Date of Birth as Text; the build uses a Date so hard gate G2 can stop a wrong date "
                            + "and the duplicate check can match name and date of birth (brief §2.4, §3.8.3) (DEV-02)."),
            new Deviation(key("Contact", "Last updated by"), Aspect.TYPE,
                    "The map types Last updated by as Text; the build stores the user who changed the marketing "
                            + "fields as a User, so it links to the user and survives a rename (DEV-04)."));

    /** Values the brief adds to a list from the map (Appendix F, §3.2: "Not yet known" feeds the review queue). */
    private static final Map<String, List<String>> BRIEF_EXTRA_VALUES = Map.of(
            "personTypes", List.of("Not yet known"));

    /** Field map "Field Type" → the data types that implement it. */
    private static final Map<String, Set<String>> TYPE_COMPATIBILITY = Map.ofEntries(
            Map.entry("text", Set.of("TEXT", "LONG_TEXT", "URL")),
            Map.entry("multi-select picklist", Set.of("MULTI_PICKLIST")),
            Map.entry("multi-choice", Set.of("MULTI_PICKLIST")),
            Map.entry("single-select picklist", Set.of("PICKLIST")),
            // Phones and Email are typed "Picklist" in the map: a labelled list whose labels are the picklist.
            Map.entry("picklist", Set.of("PICKLIST", "PHONE_LIST", "EMAIL_LIST")),
            Map.entry("date", Set.of("DATE")),
            Map.entry("checkbox", Set.of("CHECKBOX", "BOOLEAN")),
            Map.entry("formula", Set.of("NUMBER")),
            Map.entry("email", Set.of("EMAIL")),
            Map.entry("numbers", Set.of("PHONE")),
            Map.entry("dependent field", Set.of("PHONE", "EMAIL")),
            Map.entry("url", Set.of("URL")));

    /* ------------------------------------------------------------------------------------------------------------
     * Tests
     * ---------------------------------------------------------------------------------------------------------- */

    @Test
    @DisplayName("every row of the client field map is classified (built, deviation or not yet built)")
    void everyFieldMapRowIsClassified() {
        List<Row> rows = FieldMapCsv.load();
        assertThat(rows).as("rows read from " + FieldMapCsv.RESOURCE).hasSizeGreaterThan(80);
        List<String> unknown = rows.stream().filter(r -> classification(r) == null).map(Row::id).toList();
        assertThat(unknown)
                .as("Field map rows this test does not know. Map each to a field in BUILT, or list it as not built.")
                .isEmpty();
        // Each built entry still names a row of the map (a renamed row would otherwise go unchecked).
        Set<String> rowKeys = rows.stream().map(r -> key(r.object(), r.field())).collect(Collectors.toSet());
        assertThat(BUILT.keySet()).as("BUILT entries with no matching field map row").allMatch(rowKeys::contains);
    }

    @TestFactory
    @DisplayName("built rows match the field map")
    Stream<DynamicNode> builtRowsMatchTheFieldMap() throws Exception {
        Map<String, Map<String, Object>> fields = personFields();
        Map<String, List<Map<String, Object>>> picklists = picklists();
        return FieldMapCsv.load().stream()
                .filter(r -> BUILT.containsKey(key(r.object(), r.field())))
                .map(r -> rowChecks(r, fields.get(BUILT.get(key(r.object(), r.field()))), picklists));
    }

    @TestFactory
    @DisplayName("rows not in the current build are reported")
    Stream<DynamicTest> rowsNotYetBuiltAreReported() {
        return FieldMapCsv.load().stream()
                .filter(r -> notBuiltReason(r) != null)
                .map(r -> DynamicTest.dynamicTest(r.id(), () -> Assumptions.abort("Not yet built: " + notBuiltReason(r))));
    }

    @Test
    @DisplayName("Address: the address form's parts are each required")
    void addressPartsAreRequired() throws Exception {
        Row address = FieldMapCsv.load().stream().filter(r -> key(r.object(), r.field()).equals(ADDRESS_ROW)).findFirst()
                .orElseThrow();
        assertThat(address.required()).isEqualTo("Required");
        Map<String, Map<String, Object>> fields = personFields();
        for (String part : ADDRESS_PARTS) {
            assertThat(fields).as("address part " + part).containsKey(part);
            assertThat(fields.get(part).get("mandatory")).as(part + " mandatory").isEqualTo(true);
        }
    }

    @Test
    @DisplayName("Entity Role: the Entity link role list holds the field map's values")
    void entityRoleListMatches() throws Exception {
        Row role = FieldMapCsv.load().stream().filter(r -> key(r.object(), r.field()).equals(ENTITY_ROLE_ROW)).findFirst()
                .orElseThrow();
        assertThat(activeLabels(picklists().get(ENTITY_ROLE_PICKLIST)))
                .as(ENTITY_ROLE_PICKLIST + " active values")
                .containsExactlyInAnyOrderElementsOf(lower(role.values()));
    }

    @Test
    @DisplayName("Linked entities: Entity, Entity type and Date linked live on the Entity link")
    void linkedEntityRowsLiveOnTheLink() {
        List<String> linkRows = FieldMapCsv.load().stream().map(r -> key(r.object(), r.field()))
                .filter(ENTITY_LINK_ROWS::containsKey).toList();
        assertThat(linkRows).containsExactlyInAnyOrderElementsOf(ENTITY_LINK_ROWS.keySet());
    }

    @Test
    @DisplayName("deviation register entries each name a built row")
    void deviationsNameBuiltRows() {
        assertThat(DEVIATIONS).allSatisfy(d -> assertThat(BUILT).as(d.row()).containsKey(d.row()));
    }

    /* ------------------------------------------------------------------------------------------------------------
     * One row's checks
     * ---------------------------------------------------------------------------------------------------------- */

    private DynamicContainer rowChecks(Row row, Map<String, Object> f, Map<String, List<Map<String, Object>>> picklists) {
        String api = BUILT.get(key(row.object(), row.field()));
        List<DynamicTest> checks = new ArrayList<>();
        checks.add(DynamicTest.dynamicTest("exists and is enabled as '" + api + "'", () -> {
            assertThat(f).as("Person field " + api).isNotNull();
            assertThat(f.get("active")).isEqualTo(true);
        }));
        if (f == null) {
            return DynamicContainer.dynamicContainer(row.id(), checks);
        }
        checks.add(DynamicTest.dynamicTest("label is '" + row.field() + "'", () ->
                assertThat(String.valueOf(f.get("label"))).isEqualToIgnoringCase(row.field())));
        checks.add(aspectTest(row, Aspect.TYPE, "data type fits '" + row.fieldType() + "'", () -> {
            Set<String> allowed = TYPE_COMPATIBILITY.get(row.fieldType().toLowerCase(Locale.ROOT));
            assertThat(allowed).as("known field map type '" + row.fieldType() + "'").isNotNull();
            assertThat(String.valueOf(f.get("dataType"))).isIn(allowed);
        }));
        checks.add(aspectTest(row, Aspect.REQUIRED, "required rule fits '" + row.required().replace('\n', ' ') + "'",
                () -> checkRequired(row, f)));
        if (!row.values().isEmpty()) {
            checks.add(DynamicTest.dynamicTest("options are the field map's values", () -> {
                String set = String.valueOf(f.get("picklist"));
                assertThat(picklists).as("picklist " + set).containsKey(set);
                List<String> expected = new ArrayList<>(row.values());
                expected.addAll(BRIEF_EXTRA_VALUES.getOrDefault(api, List.of()));
                assertThat(activeLabels(picklists.get(set))).containsExactlyInAnyOrderElementsOf(lower(expected));
            }));
        }
        String arm = switch (row.businessUnit().toLowerCase(Locale.ROOT)) {
            case "credit" -> "CREDIT";
            case "capital" -> "CAPITAL";
            case "both" -> "BOTH";
            default -> null;
        };
        if (arm != null) {
            checks.add(DynamicTest.dynamicTest("business unit is " + row.businessUnit(), () ->
                    assertThat(f.get("arm")).isEqualTo(arm)));
        }
        if (!row.block().isBlank()) {
            String section = row.block().replace("\\", " / ");
            checks.add(aspectTest(row, Aspect.SECTION, "sits in the '" + section + "' block", () ->
                    assertThat(String.valueOf(f.get("section"))).isEqualToIgnoringCase(section)));
        }
        return DynamicContainer.dynamicContainer(row.id(), checks);
    }

    /** The check as written, or, for a registered deviation, a check that the build still differs. */
    private static DynamicTest aspectTest(Row row, Aspect aspect, String name, ThrowingCheck check) {
        Deviation d = DEVIATIONS.stream().filter(x -> x.row().equals(key(row.object(), row.field())) && x.aspect() == aspect)
                .findFirst().orElse(null);
        if (d == null) {
            return DynamicTest.dynamicTest(name, check::run);
        }
        return DynamicTest.dynamicTest(name + " [documented deviation]", () -> {
            boolean matches;
            try {
                check.run();
                matches = true;
            } catch (AssertionError e) {
                matches = false;
            }
            if (matches) {
                fail("The build now matches the field map for " + row.id() + " (" + aspect
                        + "). Remove the deviation from the register: " + d.reason());
            }
            Assumptions.abort("Documented deviation: " + d.reason());
        });
    }

    @FunctionalInterface
    private interface ThrowingCheck {
        void run() throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static void checkRequired(Row row, Map<String, Object> f) {
        boolean mandatory = Boolean.TRUE.equals(f.get("mandatory"));
        Map<String, Object> rules = f.get("displayRules") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        boolean requiredWhen = rules.get("requiredWhen") instanceof Collection<?> c && !c.isEmpty();
        boolean visibleWhen = rules.get("visibleWhen") instanceof Collection<?> c && !c.isEmpty();
        boolean computed = f.get("formula") instanceof Map<?, ?> fm && !fm.isEmpty();
        switch (need(row)) {
            case MANDATORY -> assertThat(mandatory).as("mandatory").isTrue();
            case OPTIONAL -> {
                assertThat(mandatory).as("mandatory").isFalse();
                assertThat(requiredWhen).as("has a requiredWhen rule").isFalse();
            }
            case CONDITIONAL_REQUIRED -> {
                assertThat(mandatory).as("mandatory (should only be required when its condition holds)").isFalse();
                assertThat(requiredWhen).as("has a requiredWhen rule").isTrue();
            }
            case CONDITIONAL_VISIBLE -> assertThat(visibleWhen).as("has a visibleWhen rule").isTrue();
            case SYSTEM -> assertThat(computed).as("is set by the CRM (has a formula)").isTrue();
        }
    }

    private enum Need { MANDATORY, OPTIONAL, CONDITIONAL_REQUIRED, CONDITIONAL_VISIBLE, SYSTEM }

    /** What the row's Required cell (with its Field rule) asks for. */
    static Need need(Row r) {
        String q = r.requiredLower();
        String rule = r.rule().toLowerCase(Locale.ROOT);
        if (q.contains("system")) {
            return Need.SYSTEM;
        }
        if (q.startsWith("dependent")) {
            return rule.contains("show field when") ? Need.CONDITIONAL_VISIBLE : Need.CONDITIONAL_REQUIRED;
        }
        if (q.startsWith("required") && (q.contains("if") || q.contains("opted") || rule.contains("required if"))) {
            return Need.CONDITIONAL_REQUIRED;
        }
        if (q.startsWith("required")) {
            return Need.MANDATORY;
        }
        if (q.startsWith("optional")) {
            return Need.OPTIONAL;
        }
        throw new IllegalArgumentException("Unknown Required value '" + r.required() + "' in " + r.id());
    }

    /* ------------------------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------------------- */

    private static void built(String object, String field, String apiName) {
        BUILT.put(key(object, field), apiName);
    }

    private static String key(String object, String field) {
        return object.trim() + " | " + field.trim();
    }

    private static String classification(Row r) {
        String k = key(r.object(), r.field());
        if (BUILT.containsKey(k)) {
            return "built";
        }
        if (k.equals(ADDRESS_ROW) || k.equals(ENTITY_ROLE_ROW) || ENTITY_LINK_ROWS.containsKey(k)) {
            return "special";
        }
        return notBuiltReason(r) != null ? "not built" : null;
    }

    private static String notBuiltReason(Row r) {
        String byRow = NOT_BUILT_ROWS.get(key(r.object(), r.field()));
        return byRow != null ? byRow : NOT_BUILT_OBJECTS.get(r.object());
    }

    private Map<String, Map<String, Object>> personFields() throws Exception {
        String json = getJson("/api/metadata/objects/person/fields").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> list = JsonPath.read(json, "$");
        Map<String, Map<String, Object>> byApi = new LinkedHashMap<>();
        list.forEach(f -> byApi.put(String.valueOf(f.get("apiName")), f));
        return byApi;
    }

    private Map<String, List<Map<String, Object>>> picklists() throws Exception {
        String json = getJson("/api/metadata").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Map<String, Object>> sets = JsonPath.read(json, "$.picklists");
        Map<String, List<Map<String, Object>>> values = new LinkedHashMap<>();
        sets.forEach((code, set) -> values.put(code, JsonPath.read(set, "$.values")));
        return values;
    }

    private static Set<String> activeLabels(List<Map<String, Object>> values) {
        Supplier<Set<String>> sorted = TreeSet::new;
        return values == null ? Set.of() : values.stream().filter(v -> Boolean.TRUE.equals(v.get("active")))
                .map(v -> String.valueOf(v.get("label")).trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(sorted));
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
    }
}
