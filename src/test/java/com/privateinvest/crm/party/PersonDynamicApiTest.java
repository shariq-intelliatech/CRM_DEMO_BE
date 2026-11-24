package com.privateinvest.crm.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Dynamic Person management: fields come from the registry, values are written by api name, and search,
 * filter, sort, paging and the picker work on any configured field. Covers the eight acceptance scenarios.
 */
class PersonDynamicApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    /* ---------- Helpers ---------- */

    private String createField(String label, String type, String extraJson) throws Exception {
        ResultActions r = postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"%s","dataType":"%s"%s}""".formatted(label, type, extraJson))
                .andExpect(status().isCreated());
        return read(r, "$.apiName");
    }

    private String fieldId(String apiName) throws Exception {
        String body = getJson("/api/metadata/objects/person/fields?includeDisabled=true").andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[?(@.apiName == '" + apiName + "')].id");
        return ids.getFirst();
    }

    private String fieldVersion(String apiName) throws Exception {
        String body = getJson("/api/metadata/objects/person/fields?includeDisabled=true").andReturn().getResponse().getContentAsString();
        List<Object> v = JsonPath.read(body, "$[?(@.apiName == '" + apiName + "')].version");
        return String.valueOf(v.getFirst());
    }

    /** A property of a field listed under Deleted fields. */
    private String deletedFieldValue(String apiName, String property) throws Exception {
        String body = getJson("/api/metadata/custom-fields/deleted").andReturn().getResponse().getContentAsString();
        List<Object> v = JsonPath.read(body, "$[?(@.apiName == '" + apiName + "')]." + property);
        return String.valueOf(v.getFirst());
    }

    /** Creates a Person from these values plus the field map's required address, phone and email. */
    private String createPerson(String valuesJson) throws Exception {
        return createPerson(valuesJson, requiredPerson());
    }

    private String createPerson(String valuesJson, String required) throws Exception {
        String values = "{" + required + "," + valuesJson.trim().substring(1);
        return read(postJson("/api/persons", "{\"duplicateReason\":\"Test data\",\"values\":" + values + "}")
                .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions patchPerson(String id, String valuesJson) throws Exception {
        return mvc.perform(patch("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + valuesJson + "}"));
    }

    private ResultActions search(String body) throws Exception {
        return postJson("/api/persons/search", body);
    }

    private static String digits(String s, int n) {
        StringBuilder d = new StringBuilder();
        for (char c : s.toCharArray()) {
            d.append(Math.abs(c) % 10);
        }
        while (d.length() < n) {
            d.append('7');
        }
        return d.substring(0, n);
    }

    /* ---------- Scenario 1: dynamic field creation ---------- */

    @Test
    void newSharedFieldAppearsInDefinitionsAndIsStoredOnSave() throws Exception {
        String u = unique();
        String api = createField("Annual Income " + u, "NUMBER",
                ",\"decimalPlaces\":0,\"placeholder\":\"e.g. 250000\",\"validation\":{\"min\":0},\"showInList\":true");

        getJson("/api/metadata/objects/person/fields")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].placeholder").value(hasItem("e.g. 250000")))
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].operators[*]").value(hasItem("BETWEEN")))
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].deletable").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.apiName == 'firstName')].system").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.apiName == 'firstName')].deletable").value(hasItem(true)));

        String id = createPerson("""
                {"firstName":"Ava","lastName":"Income%s","personTypes":["INVESTOR"],"%s":250000}""".formatted(u, api));
        String stored = jdbc.sql("SELECT custom_fields ->> :k FROM person WHERE id = CAST(:id AS uuid)")
                .param("k", api).param("id", id).query(String.class).single();
        assertThat(stored).isEqualTo("250000");
        getJson("/api/persons/" + id).andExpect(jsonPath("$.values." + api).value(250000));

        // The field's own rules apply: minimum 0, whole numbers.
        postJson("/api/persons", """
                {"values":{"firstName":"Neg","lastName":"Income%s","personTypes":["BROKER"],"%s":-5}}""".formatted(u, api))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + api, containsString("0 or more")));
    }

    /* ---------- Scenario 2: dynamic field update ---------- */

    @Test
    void changingAFieldKeepsExistingValues() throws Exception {
        String u = unique();
        String api = createField("Tier " + u, "TEXT", "");
        String id = createPerson("""
                {"firstName":"Bo","lastName":"Tier%s","personTypes":["BROKER"],"%s":"Gold"}""".formatted(u, api));

        putJson("/api/metadata/custom-fields/" + fieldId(api), """
                {"label":"Client tier %s","help":"Shown on the record","placeholder":"Gold, Silver...","width":"FULL",
                 "version":%s}""".formatted(u, fieldVersion(api)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Client tier " + u))
                .andExpect(jsonPath("$.apiName").value(api))
                .andExpect(jsonPath("$.width").value("FULL"));

        getJson("/api/persons/" + id).andExpect(jsonPath("$.values." + api).value("Gold"));
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].label").value(hasItem("Client tier " + u)));
    }

    /* ---------- Scenario 3: record-level fields ---------- */

    @Test
    void recordLevelFieldsStayOnTheirOwnPerson() throws Exception {
        String u = unique();
        String a = read(postJson("/api/persons", """
                {"duplicateReason":"Test data","values":{"firstName":"Anna","lastName":"Rec%s","personTypes":["BROKER"],%s},
                 "recordFields":[{"label":"Assistant Name","dataType":"TEXT","value":"John"}]}""".formatted(u, requiredPerson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordFields[0].label").value("Assistant Name")), "$.id");
        String b = createPerson("""
                {"firstName":"Ben","lastName":"Rec%s","personTypes":["BROKER"]}""".formatted(u));

        getJson("/api/persons/" + b).andExpect(jsonPath("$.recordFields", hasSize(0)));
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[*].label").value(not(hasItem("Assistant Name"))));
        getJson("/api/persons/" + a).andExpect(jsonPath("$.recordFields[0].value").value("John"));
    }

    /* ---------- Scenario 4: Person CRUD ---------- */

    @Test
    void createViewUpdateArchiveAndRestore() throws Exception {
        String u = unique();
        String api = createField("Nickname " + u, "TEXT", "");
        String fixed = createField("Account code " + u, "TEXT", ",\"editableAfterCreate\":false");
        String mobile = "04" + digits(u, 8);
        String id = read(postJson("/api/persons", """
                {"duplicateReason":"Test data","values":{"firstName":"Cleo","lastName":"Crud%s","personTypes":["INVESTOR"],%s,
                  "dateOfBirth":"1984-03-09","%s":"AC-1","%s":"CC"},
                 "recordFields":[{"label":"Preferred time","dataType":"TEXT","value":"Morning"}]}"""
                .formatted(u, requiredPerson("cleo." + u + "@example.test", mobile), fixed, api))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values.emails[0].value").value("cleo." + u + "@example.test"))
                .andExpect(jsonPath("$.values.phones[0].value").value(mobile))
                .andExpect(jsonPath("$.values.ownerId").value("u1"))
                .andExpect(jsonPath("$.values.country").value("Australia"))
                .andExpect(jsonPath("$.title").value("Cleo Crud" + u)), "$.id");

        patchPerson(id, """
                {"dateOfBirth":"1984-03-10","emails":[{"label":"PERSONAL","value":"cleo.new.%s@example.test"}],"%s":null,"state":"NSW"}"""
                .formatted(u, api))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.dateOfBirth").value("1984-03-10"))
                .andExpect(jsonPath("$.values.emails[0].value").value("cleo.new." + u + "@example.test"))
                .andExpect(jsonPath("$.values.emails[0].label").value("PERSONAL"))
                .andExpect(jsonPath("$.values.state").value("NSW"))
                .andExpect(jsonPath("$.values." + api).doesNotExist());

        // A field fixed after creation can't change; a required field can't be cleared.
        patchPerson(id, "{\"" + fixed + "\":\"AC-2\",\"lastName\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + fixed).exists())
                .andExpect(jsonPath("$.errors.lastName").exists());
        // A stale screen can't overwrite a newer save.
        mvc.perform(patch("/api/persons/" + id).header("If-Match", "0").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"dateOfBirth\":\"1990-01-01\"}}")).andExpect(status().isConflict());

        mvc.perform(delete("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Left the industry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        getJson("/api/persons/" + id).andExpect(status().isNotFound());
        String archivedVersion = read(getJson("/api/persons/" + id + "?includeArchived=true")
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.recordFields[0].value").value("Morning")), "$.version");
        search("""
                {"q":"Crud%s"}""".formatted(u)).andExpect(jsonPath("$.totalElements").value(0));
        search("""
                {"q":"Crud%s","scope":"ARCHIVED"}""".formatted(u)).andExpect(jsonPath("$.totalElements").value(1));
        getAs("u2", "/api/persons/" + id + "?includeArchived=true").andExpect(status().isNotFound());

        mvc.perform(post("/api/persons/" + id + "/restore").header("If-Match", archivedVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.values.state").value("NSW"));
        List<String> actions = jdbc.sql("SELECT action FROM audit_event WHERE record_id = CAST(:id AS uuid) ORDER BY id")
                .param("id", id).query(String.class).list();
        assertThat(actions).containsExactly("create", "update", "archive", "restore");
    }

    @Test
    void standardUsersCannotArchive() throws Exception {
        String id = createPerson("""
                {"firstName":"Dee","lastName":"NoArchive%s","personTypes":["BROKER"]}""".formatted(unique()));
        mvc.perform(delete("/api/persons/" + id).header("X-Dev-User", "u2").header("If-Match", versionOf("/api/persons/" + id)))
                .andExpect(status().isForbidden());
    }

    /* ---------- Scenario 5: dynamic search ---------- */

    @Test
    void quickSearchMatchesNamesEmailsPhonesAndSearchableFields() throws Exception {
        String u = unique();
        String api = createField("Alias " + u, "TEXT", ",\"searchable\":true");
        String mobile = "04" + digits(u + "x", 8);
        createPerson("""
                {"firstName":"Olivia","lastName":"Chen%s","personTypes":["INVESTOR"],"%s":"Livvy%s"}""".formatted(u, api, u),
                requiredPerson("olivia." + u + "@calder.test", mobile));

        search("{\"q\":\"olivia chen" + u + "\"}").andExpect(jsonPath("$.totalElements").value(1));
        search("{\"q\":\"CHEN" + u.toUpperCase() + "\"}").andExpect(jsonPath("$.totalElements").value(1));
        search("{\"q\":\"olivia." + u + "@cal\"}").andExpect(jsonPath("$.totalElements").value(1));
        search("{\"q\":\"" + mobile.substring(2, 9) + "\"}").andExpect(jsonPath("$.items[*].title").value(hasItem("Olivia Chen" + u)));
        search("{\"q\":\"livvy" + u + "\"}").andExpect(jsonPath("$.totalElements").value(1));

        // Exact matching through a filter; case-insensitive.
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"lastName","operator":"EQUALS","value":"chen%s"}]}}""".formatted(u))
                .andExpect(jsonPath("$.totalElements").value(1));
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"lastName","operator":"EQUALS","value":"chen"}]}}""")
                .andExpect(jsonPath("$.items[*].title").value(not(hasItem("Olivia Chen" + u))));
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"lastName","operator":"STARTS_WITH","value":"Chen%s"}]}}""".formatted(u))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /* ---------- Scenario 6: dynamic filtering, sorting, paging ---------- */

    @Test
    void filtersCombineWithAndOrAndSortAndPage() throws Exception {
        String u = unique();
        String income = createField("Income " + u, "CURRENCY", ",\"showInList\":true");
        createPerson("""
                {"firstName":"A","lastName":"Flt%s","personTypes":["INVESTOR"],"dateOfBirth":"1970-05-01","%s":100000}""".formatted(u, income));
        createPerson("""
                {"firstName":"B","lastName":"Flt%s","personTypes":["INVESTOR","BROKER"],"dateOfBirth":"1985-05-01","%s":300000}""".formatted(u, income));
        createPerson("""
                {"firstName":"C","lastName":"Flt%s","personTypes":["BROKER"],"dateOfBirth":"1995-05-01","%s":500000}""".formatted(u, income));
        String mine = "{\"field\":\"lastName\",\"operator\":\"EQUALS\",\"value\":\"Flt" + u + "\"}";

        search("""
                {"filter":{"op":"AND","conditions":[%s,{"field":"personTypes","operator":"CONTAINS_ANY","value":["INVESTOR"]}]}}"""
                .formatted(mine)).andExpect(jsonPath("$.totalElements").value(2));
        search("""
                {"filter":{"op":"AND","conditions":[%s,{"field":"dateOfBirth","operator":"BEFORE","value":"1990-01-01"}]}}"""
                .formatted(mine)).andExpect(jsonPath("$.totalElements").value(2));
        search("""
                {"filter":{"op":"AND","conditions":[%s,{"field":"%s","operator":"BETWEEN","value":[200000,600000]}]}}"""
                .formatted(mine, income)).andExpect(jsonPath("$.totalElements").value(2));
        // Person Type = Broker AND (income > 400k OR born before 1990)
        search("""
                {"filter":{"op":"AND","conditions":[%s,{"field":"personTypes","operator":"CONTAINS_ANY","value":["BROKER"]},
                  {"op":"OR","conditions":[{"field":"%s","operator":"GT","value":400000},
                                           {"field":"dateOfBirth","operator":"BEFORE","value":"1990-01-01"}]}]},
                 "sort":[{"field":"%s","dir":"DESC"}]}""".formatted(mine, income, income))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].title").value("C Flt" + u))
                .andExpect(jsonPath("$.items[1].title").value("B Flt" + u));

        // Paging: one per page, stable order, sorted by income ascending.
        search("""
                {"filter":{"op":"AND","conditions":[%s]},"sort":[{"field":"%s","dir":"ASC"}],"page":1,"size":1}"""
                .formatted(mine, income))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("B Flt" + u))
                .andExpect(jsonPath("$.items[0].values." + income).value(300000));
    }

    @Test
    void unsafeOrInvalidSearchesAreRefused() throws Exception {
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"x'; DROP TABLE person;--","operator":"EQUALS","value":"a"}]}}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.filter").exists());
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"lastName","operator":"GT","value":"a"}]}}""")
                .andExpect(status().isBadRequest());
        search("""
                {"filter":{"op":"AND","conditions":[{"field":"dateOfBirth","operator":"BEFORE","value":"not a date"}]}}""")
                .andExpect(status().isBadRequest());
        search("{\"sort\":[{\"field\":\"lastName\",\"dir\":\"DESC; DELETE\"}]}").andExpect(status().isBadRequest());
        search("{\"sort\":[{\"field\":\"personTypes\"}]}").andExpect(status().isBadRequest());
        search("{\"size\":1000}").andExpect(status().isBadRequest());
        search("{\"scope\":\"ARCHIVED\"}".replace("}", ",\"size\":5}"));
        mvc.perform(post("/api/persons/search").header("X-Dev-User", "u2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"ARCHIVED\"}")).andExpect(status().isForbidden());
        // Wildcards in a search are literal characters, not "match anything".
        String u = unique();
        createPerson("{\"firstName\":\"Pct\",\"lastName\":\"Wild%s\",\"personTypes\":[\"BROKER\"]}".formatted(u));
        search("{\"q\":\"Wild%\"}").andExpect(jsonPath("$.items[*].title").value(not(hasItem("Pct Wild" + u))));
        search("{\"q\":\"W_ld" + u + "\"}").andExpect(jsonPath("$.totalElements").value(0));
        assertThat(jdbc.sql("SELECT count(*) FROM person").query(Long.class).single()).isPositive();
    }

    /* ---------- Scenario 7: Person picker ---------- */

    @Test
    void lookupPagesThroughMatchesWithoutLoadingEverything() throws Exception {
        String u = unique();
        String first = createPerson("{\"firstName\":\"Pia\",\"lastName\":\"Pick%sa\",\"personTypes\":[\"INVESTOR\"]}".formatted(u),
                requiredPerson("pia." + u + "@x.test", "04" + randomDigits(8)));
        createPerson("{\"firstName\":\"Pia\",\"lastName\":\"Pick%sb\",\"personTypes\":[\"BROKER\"]}".formatted(u));
        createPerson("{\"firstName\":\"Pia\",\"lastName\":\"Pick%sc\",\"personTypes\":[\"BROKER\"]}".formatted(u));

        getJson("/api/persons/lookup?q=pick" + u + "&size=2")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].title").value("Pia Pick" + u + "a"))
                .andExpect(jsonPath("$.items[0].subtitle").value("Investor · pia." + u + "@x.test"));
        getJson("/api/persons/lookup?q=pick" + u + "&size=2&page=1")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false));
        getJson("/api/persons/lookup?q=pick" + u + "&exclude=" + first)
                .andExpect(jsonPath("$.items", hasSize(2)));
        getJson("/api/persons/lookup?ids=" + first)
                .andExpect(jsonPath("$.items[0].id").value(first));
        getJson("/api/persons/lookup?size=500").andExpect(status().isBadRequest());
    }

    /* ---------- Scenario 8: disabling and deleting fields ---------- */

    @Test
    void disablingAndSoftDeletingKeepValuesAndRestoreBringsThemBack() throws Exception {
        String u = unique();
        String api = createField("Hobby " + u, "TEXT", "");
        String a = createPerson("{\"firstName\":\"E\",\"lastName\":\"Dis%s\",\"personTypes\":[\"BROKER\"],\"%s\":\"Golf\"}".formatted(u, api));
        String b = createPerson("{\"firstName\":\"F\",\"lastName\":\"Dis%s\",\"personTypes\":[\"BROKER\"],\"%s\":\"Wine\"}".formatted(u, api));
        String id = fieldId(api);

        putJson("/api/metadata/custom-fields/" + id + "/active", "{\"active\":false}").andExpect(status().isOk());
        getJson("/api/metadata/objects/person/fields").andExpect(jsonPath("$[*].apiName").value(not(hasItem(api))));
        getJson("/api/persons/" + a).andExpect(jsonPath("$.values." + api).doesNotExist());
        patchPerson(a, "{\"" + api + "\":\"Tennis\"}").andExpect(status().isBadRequest());
        search("{\"filter\":{\"op\":\"AND\",\"conditions\":[{\"field\":\"" + api + "\",\"operator\":\"EQUALS\",\"value\":\"Golf\"}]}}")
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT custom_fields ->> :k FROM person WHERE id = CAST(:id AS uuid)")
                .param("k", api).param("id", a).query(String.class).single()).isEqualTo("Golf");

        putJson("/api/metadata/custom-fields/" + id + "/active", "{\"active\":true}").andExpect(status().isOk());
        getJson("/api/persons/" + a).andExpect(jsonPath("$.values." + api).value("Golf"));
        getJson("/api/persons/" + b).andExpect(jsonPath("$.values." + api).value("Wine"));

        // Deleting is a soft delete: the field leaves every screen, its values stay, and it is listed as deleted.
        getJson("/api/metadata/custom-fields/" + id + "/usage").andExpect(jsonPath("$.recordsWithValue").value(2));
        mvc.perform(delete("/api/metadata/custom-fields/" + id).header("If-Match", fieldVersion(api)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permanent").value(false))
                .andExpect(jsonPath("$.valuesKept").value(2));
        getJson("/api/metadata/objects/person/fields?includeDisabled=true").andExpect(jsonPath("$[*].apiName").value(not(hasItem(api))));
        getJson("/api/persons/" + a).andExpect(jsonPath("$.values." + api).doesNotExist());
        patchPerson(b, "{\"" + api + "\":\"Back\"}").andExpect(status().isBadRequest());
        getJson("/api/metadata/custom-fields/deleted")
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].recordsWithValue").value(hasItem(2)))
                .andExpect(jsonPath("$[?(@.apiName == '" + api + "')].deletedByName").value(hasItem("Sophie Turner")));
        assertThat(jdbc.sql("SELECT custom_fields ->> :k FROM person WHERE id = CAST(:id AS uuid)")
                .param("k", api).param("id", a).query(String.class).single()).isEqualTo("Golf");
        // Standard users can't see or restore deleted fields; a deleted field can't be edited or enabled.
        getAs("u2", "/api/metadata/custom-fields/deleted").andExpect(status().isForbidden());
        putJson("/api/metadata/custom-fields/" + id + "/active", "{\"active\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("field-deleted"));

        // Restore brings the field and every value back.
        mvc.perform(post("/api/metadata/custom-fields/" + id + "/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
        getJson("/api/persons/" + a).andExpect(jsonPath("$.values." + api).value("Golf"));
        getJson("/api/persons/" + b).andExpect(jsonPath("$.values." + api).value("Wine"));

        // Permanent delete only from Deleted fields: it removes the values from every Person.
        mvc.perform(delete("/api/metadata/custom-fields/" + id + "?permanent=true").header("If-Match", fieldVersion(api)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not-deleted"));
        mvc.perform(delete("/api/metadata/custom-fields/" + id).header("If-Match", fieldVersion(api))).andExpect(status().isOk());
        String deletedVersion = deletedFieldValue(api, "version");
        String versionBefore = versionOf("/api/persons/" + a);
        mvc.perform(delete("/api/metadata/custom-fields/" + id + "?permanent=true").header("If-Match", deletedVersion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valuesDeleted").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM person WHERE (custom_fields -> :k) IS NOT NULL")
                .param("k", api).query(Long.class).single()).isZero();
        assertThat(versionOf("/api/persons/" + a)).isNotEqualTo(versionBefore);   // open screens become stale
        getJson("/api/metadata/custom-fields/deleted").andExpect(jsonPath("$[*].apiName").value(not(hasItem(api))));

        // A deleted field's label is free again; restoring the old one then asks for a rename.
        String other = createField("Pet " + u, "TEXT", "");
        mvc.perform(delete("/api/metadata/custom-fields/" + fieldId(other)).header("If-Match", fieldVersion(other)))
                .andExpect(status().isOk());
        createField("Pet " + u, "TEXT", "");
        String oldId = deletedFieldValue(other, "id");
        mvc.perform(post("/api/metadata/custom-fields/" + oldId + "/restore"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("label-taken"));

        // Former system fields behave like any other.
        putJson("/api/metadata/custom-fields/" + fieldId("dateOfBirth") + "/active", "{\"active\":false}").andExpect(status().isOk());
        putJson("/api/metadata/custom-fields/" + fieldId("dateOfBirth") + "/active", "{\"active\":true}").andExpect(status().isOk());
    }

    @Test
    void systemFieldsCanBeRelabelledAndReordered() throws Exception {
        String id = fieldId("dateOfBirth");
        putJson("/api/metadata/custom-fields/" + id, """
                {"label":"Birth date","mandatory":false,"placeholder":"YYYY-MM-DD","version":%s}""".formatted(fieldVersion("dateOfBirth")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataType").value("DATE"))
                .andExpect(jsonPath("$.storage").value("CUSTOM"));
        getJson("/api/metadata").andExpect(jsonPath("$.fields[?(@.apiName == 'dateOfBirth')].label").value(hasItem("Birth date")));
        putJson("/api/metadata/custom-fields/" + id, """
                {"label":"Date of Birth","mandatory":false,"version":%s}""".formatted(fieldVersion("dateOfBirth")))
                .andExpect(status().isOk());

        String first = fieldId("firstName");
        String last = fieldId("lastName");
        putJson("/api/metadata/custom-fields/order", """
                {"object":"person","fieldIds":["%s","%s"]}""".formatted(last, first)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiName").value("lastName"));
        putJson("/api/metadata/custom-fields/order", """
                {"object":"person","fieldIds":["%s","%s"]}""".formatted(first, last)).andExpect(status().isOk());
    }

    @Test
    void quickSearchCoversEveryCustomFieldTypeAndOneOffFields() throws Exception {
        String u = unique();
        String income = createField("Income qs " + u, "CURRENCY", ",\"decimalPlaces\":0");
        String review = createField("Review qs " + u, "DATE", "");
        String tier = createField("Tier qs " + u, "PICKLIST", ",\"picklistValues\":[\"Platinum " + u + "\",\"Silver\"]");
        String hobbies = createField("Hobbies qs " + u, "MULTI_PICKLIST", ",\"picklistValues\":[\"Sailing " + u + "\",\"Chess\"]");
        String quiet = createField("Quiet qs " + u, "TEXT", ",\"searchable\":false");
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[?(@.apiName == '" + income + "')].searchable").value(hasItem(true)));
        String tierCode = jdbc.sql("SELECT code FROM picklist_value pv JOIN custom_field_def d ON d.picklist_set_code = pv.set_code "
                + "WHERE d.api_name = :a AND pv.label LIKE 'Platinum%'").param("a", tier).query(String.class).single();
        String hobbyCode = jdbc.sql("SELECT code FROM picklist_value pv JOIN custom_field_def d ON d.picklist_set_code = pv.set_code "
                + "WHERE d.api_name = :a AND pv.label LIKE 'Sailing%'").param("a", hobbies).query(String.class).single();
        String amount = "7" + digits(u, 6);
        String id = read(postJson("/api/persons", """
                {"duplicateReason":"Test data","values":{"firstName":"Quinn","lastName":"Qs%s","personTypes":["BROKER"],%s,
                  "%s":%s,"%s":"2031-02-17","%s":"%s","%s":["%s"],"%s":"Hidden%s"},
                 "recordFields":[{"label":"Assistant %s","dataType":"TEXT","value":"Rosalind%s"}]}"""
                .formatted(u, requiredPerson(), income, amount, review, tier, tierCode, hobbies, hobbyCode, quiet, u, u, u))
                .andExpect(status().isCreated()), "$.id");
        String one = "$.items[?(@.id == '" + id + "')]";

        search("{\"q\":\"$" + amount.charAt(0) + "," + amount.substring(1, 4) + "," + amount.substring(4) + "\"}")
                .andExpect(jsonPath(one).exists());                              // currency typed with $ and commas
        search("{\"q\":\"17/02/2031\"}").andExpect(jsonPath(one).exists());     // Australian date format
        search("{\"q\":\"platinum " + u + "\"}").andExpect(jsonPath(one).exists()); // dropdown label, not code
        search("{\"q\":\"sailing " + u + "\"}").andExpect(jsonPath(one).exists());  // multi-select label
        search("{\"q\":\"rosalind" + u + "\"}").andExpect(jsonPath(one).exists());  // one-off field value
        search("{\"q\":\"assistant " + u + "\"}").andExpect(jsonPath(one).exists()); // one-off field label
        search("{\"q\":\"hidden" + u + "\"}").andExpect(jsonPath("$.totalElements").value(0)); // taken out of search
        search("{\"q\":\"" + tierCode + "\"}").andExpect(jsonPath(one).doesNotExist());    // codes aren't searched
        getJson("/api/persons/lookup?q=rosalind" + u).andExpect(jsonPath("$.items[0].id").value(id));
    }
}
