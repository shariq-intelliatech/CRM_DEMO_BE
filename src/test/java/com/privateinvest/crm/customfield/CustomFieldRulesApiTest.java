package com.privateinvest.crm.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Production rules for custom fields: mandatory fields, defaults, the Boolean type, sensitive fields and
 * permissions, record-type isolation, and the database constraints behind them.
 */
class CustomFieldRulesApiTest extends ApiTestBase {

    /** Posts a new Person with the field map's required address, phone and email added to the body. */
    private org.springframework.test.web.servlet.ResultActions postPerson(String body) throws Exception {
        return postJson("/api/persons", "{" + requiredPerson() + "," + body.trim().substring(1));
    }

    @Autowired
    private JdbcClient jdbc;

    private ResultActions createField(String object, String label, String type, String extra) throws Exception {
        return postJson("/api/metadata/custom-fields", """
                {"object":"%s","label":"%s","dataType":"%s"%s}""".formatted(object, label, type, extra));
    }

    private void retire(String fieldId) throws Exception {
        putJson("/api/metadata/custom-fields/" + fieldId + "/active", "{\"active\":false}").andExpect(status().isOk());
    }

    @Test
    void mandatoryFieldBlocksCreateAndClearing() throws Exception {
        ResultActions created = createField("entity", "Risk category " + unique(), "PICKLIST",
                ",\"mandatory\":true,\"picklistValues\":[\"Low\",\"Medium\",\"High\"]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.mandatory").value(true));
        String id = read(created, "$.id");
        String api = read(created, "$.apiName");
        try {
            postJson("/api/entities", """
                    {"name":"No Risk %s","acn":"%s","entityType":"DEVELOPER"}""".formatted(unique(), validAcn()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + api, containsString("is required")));

            String entity = read(postJson("/api/entities", """
                    {"name":"With Risk %s","acn":"%s","entityType":"DEVELOPER","customFields":{"%s":"HIGH"}}"""
                    .formatted(unique(), validAcn(), api))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values." + api).value("HIGH")), "$.id");

            putCurrent("/api/entities/" + entity + "/custom-fields", "{\"values\":{\"%s\":null}}".formatted(api))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + api, containsString("is required")));
            putCurrent("/api/entities/" + entity + "/custom-fields", "{\"values\":{\"%s\":\"LOW\"}}".formatted(api))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.values." + api).value("LOW"));
        } finally {
            retire(id); // so other tests can still create Entities without this field
        }
        // Once retired, the field no longer blocks saves.
        postJson("/api/entities", """
                {"name":"After Retire %s","acn":"%s","entityType":"DEVELOPER"}""".formatted(unique(), validAcn()))
                .andExpect(status().isCreated());
    }

    @Test
    void fieldMadeMandatoryLaterDoesNotBlockEditingOtherFields() throws Exception {
        String u = unique();
        String lateApi = read(createField("entity", "Late Req " + u, "TEXT", "")
                .andExpect(status().isCreated()), "$.apiName");
        String lateId = read(getJson("/api/metadata/custom-fields?object=entity"),
                "$[?(@.apiName == '" + lateApi + "')].id").replaceAll("[\\[\\]\"]", "");
        String entity = read(postJson("/api/entities", """
                {"name":"Legacy Co %s","acn":"%s","entityType":"DEVELOPER"}""".formatted(u, validAcn()))
                .andExpect(status().isCreated()), "$.id");

        // Admin later marks the field mandatory on existing records that never had a value for it.
        String version = read(getJson("/api/metadata/custom-fields?object=entity"),
                "$[?(@.apiName == '" + lateApi + "')].version").replaceAll("[\\[\\]]", "");
        putJson("/api/metadata/custom-fields/" + lateId, """
                {"label":"Late Req %s","mandatory":true,"version":%s}""".formatted(u, version))
                .andExpect(status().isOk());

        try {
            // Updating another field still succeeds without forcing the untouched legacy gap.
            putCurrent("/api/entities/" + entity + "/custom-fields", "{\"values\":{\"capitalStatus\":\"ACTIVE\"}}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.values.capitalStatus").value("ACTIVE"));

            // Explicitly clearing or sending blank for that mandatory field still fails.
            putCurrent("/api/entities/" + entity + "/custom-fields", "{\"values\":{\"%s\":\"\"}}".formatted(lateApi))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + lateApi, containsString("is required")));
        } finally {
            retire(lateId);
        }
    }

    @Test
    void defaultsApplyOnCreateAndCanBeOverridden() throws Exception {
        ResultActions created = createField("person", "Segment " + unique(), "PICKLIST",
                ",\"defaultValue\":\"Retail\",\"picklistValues\":[\"Retail\",\"Wholesale\"]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultValue").value("RETAIL"));
        String id = read(created, "$.id");
        String api = read(created, "$.apiName");
        String limit = read(createField("person", "Limit " + unique(), "CURRENCY", ",\"defaultValue\":\"250000\""), "$.apiName");
        String limitId = read(getJson("/api/metadata/custom-fields?object=person"), "$[?(@.apiName == '" + limit + "')].id")
                .replaceAll("[\\[\\]\"]", "");
        try {
            postPerson("""
                    {"firstName":"Def","lastName":"Aults%s","personTypes":["INVESTOR"]}""".formatted(unique()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values." + api).value("RETAIL"))
                    .andExpect(jsonPath("$.values." + limit).value(250000));
            postPerson("""
                    {"firstName":"Over","lastName":"Ride%s","personTypes":["INVESTOR"],"customFields":{"%s":"WHOLESALE","%s":null}}"""
                    .formatted(unique(), api, limit))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values." + api).value("WHOLESALE"))
                    .andExpect(jsonPath("$.values." + limit).doesNotExist());
        } finally {
            retire(id);
            retire(limitId);
        }
    }

    @Test
    void defaultMustSuitTheField() throws Exception {
        createField("person", "Bad default " + unique(), "NUMBER", ",\"decimalPlaces\":0,\"defaultValue\":\"1.5\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultValue", containsString("whole number")));
        createField("person", "Bad option " + unique(), "PICKLIST", ",\"defaultValue\":\"Purple\",\"picklistValues\":[\"Red\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultValue").exists());
    }

    @Test
    void booleanKeepsTrueAndFalse() throws Exception {
        String api = read(createField("person", "Has SMSF " + unique(), "BOOLEAN", ""), "$.apiName");
        String person = read(postPerson("""
                {"firstName":"Bool","lastName":"Ean%s","personTypes":["INVESTOR"],"customFields":{"%s":false}}"""
                .formatted(unique(), api)).andExpect(status().isCreated()), "$.id");
        getJson("/api/persons/" + person).andExpect(jsonPath("$.values." + api).value(false));
        putCurrent("/api/persons/" + person + "/custom-fields", "{\"values\":{\"%s\":\"yes\"}}".formatted(api))
                .andExpect(jsonPath("$.values." + api).value(true));
        putCurrent("/api/persons/" + person + "/custom-fields", "{\"values\":{\"%s\":\"maybe\"}}".formatted(api))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + api, containsString("Yes or No")));
    }

    @Test
    void sensitiveValuesNeedPermission() throws Exception {
        String api = read(createField("person", "Annual income " + unique(), "CURRENCY", ",\"sensitive\":true"), "$.apiName");
        String person = read(postPerson("""
                {"firstName":"Sens","lastName":"Itive%s","personTypes":["INVESTOR"],"customFields":{"%s":"180000"}}"""
                .formatted(unique(), api)).andExpect(status().isCreated()), "$.id");

        getJson("/api/persons/" + person).andExpect(jsonPath("$.values." + api).value(180000));
        getAs("u2", "/api/persons/" + person).andExpect(jsonPath("$.values." + api).doesNotExist());
        putCurrentAs("u2", "/api/persons/" + person + "/custom-fields", "{\"values\":{\"%s\":\"1\"}}".formatted(api))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        // The value is untouched.
        getJson("/api/persons/" + person).andExpect(jsonPath("$.values." + api).value(180000));
    }

    @Test
    void onlyManagersCanChangeDefinitions() throws Exception {
        postAs("u2", "/api/metadata/custom-fields", """
                {"object":"person","label":"Sneaky %s","dataType":"TEXT"}""".formatted(unique()))
                .andExpect(status().isForbidden());
        ResultActions created = createField("person", "Managed " + unique(), "PICKLIST", ",\"picklistValues\":[\"A\"]");
        String id = read(created, "$.id");
        String set = read(created, "$.picklist");
        putAs("u2", "/api/metadata/custom-fields/" + id, "{\"label\":\"Renamed\",\"version\":0}").andExpect(status().isForbidden());
        putAs("u2", "/api/metadata/custom-fields/" + id + "/active", "{\"active\":false}").andExpect(status().isForbidden());
        postAs("u2", "/api/metadata/picklists/" + set + "/values", "{\"label\":\"B\"}").andExpect(status().isForbidden());

        // Standard users work with Persons but can't manage fields or see sensitive values.
        getAs("u2", "/api/me").andExpect(jsonPath("$.permissions", not(hasItem("CUSTOM_FIELD_MANAGE"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("CUSTOM_FIELD_SENSITIVE")))).andExpect(jsonPath("$.roles", hasItem("USER")));
        getJson("/api/me").andExpect(jsonPath("$.permissions", hasItem("CUSTOM_FIELD_MANAGE")));
    }

    @Test
    void fieldsAreIsolatedByRecordType() throws Exception {
        String entityField = read(createField("entity", "Entity only " + unique(), "TEXT", ""), "$.apiName");
        String person = read(postPerson("""
                {"firstName":"Iso","lastName":"Lated%s","personTypes":["BROKER"]}""".formatted(unique()))
                .andExpect(status().isCreated()), "$.id");
        putCurrent("/api/persons/" + person + "/custom-fields", "{\"values\":{\"%s\":\"x\"}}".formatted(entityField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + entityField, containsString("no person custom field")));
    }

    @Test
    void databaseRejectsMismatchedOrInvalidValues() throws Exception {
        String entityField = read(createField("entity", "Db guard " + unique(), "TEXT", ""), "$.apiName");
        String person = read(postPerson("""
                {"firstName":"Db","lastName":"Guard%s","personTypes":["BROKER"]}""".formatted(unique()))
                .andExpect(status().isCreated()), "$.id");
        String write = "UPDATE person SET custom_fields = CAST(:doc AS jsonb) WHERE id = CAST(:p AS uuid)";

        // An Entity field can't hold a Person value, even with hand-written SQL (trigger on person).
        assertThatThrownBy(() -> jdbc.sql(write).param("doc", "{\"%s\":\"x\"}".formatted(entityField))
                .param("p", person).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // A dropdown value must be a real option of the field's list.
        String pick = read(createField("person", "Db option " + unique(), "PICKLIST", ",\"picklistValues\":[\"Yes\"]"),
                "$.apiName");
        assertThatThrownBy(() -> jdbc.sql(write).param("doc", "{\"%s\":\"NOT_AN_OPTION\"}".formatted(pick))
                .param("p", person).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql(write).param("doc", "{\"%s\":\"YES\"}".formatted(pick)).param("p", person).update();

        // A value must have its field's type.
        String number = read(createField("person", "Db number " + unique(), "NUMBER", ""), "$.apiName");
        assertThatThrownBy(() -> jdbc.sql(write).param("doc", "{\"%s\":\"lots\"}".formatted(number))
                .param("p", person).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT custom_fields ->> :k FROM person WHERE id = CAST(:p AS uuid)")
                .param("k", pick).param("p", person).query(String.class).single()).isEqualTo("YES");
    }
}
