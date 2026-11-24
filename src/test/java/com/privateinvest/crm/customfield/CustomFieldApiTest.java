package com.privateinvest.crm.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Runtime custom fields: definitions in Setup, typed values on Person and Entity records. */
class CustomFieldApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    private ResultActions createField(String object, String label, String type, String extra) throws Exception {
        return postJson("/api/metadata/custom-fields", """
                {"object":"%s","label":"%s","dataType":"%s"%s}""".formatted(object, label, type, extra));
    }

    private String newPerson() throws Exception {
        return read(postJson("/api/persons", """
                {"firstName":"Custom","lastName":"Holder%s","personTypes":["INVESTOR"],%s}""".formatted(unique(), requiredPerson()))
                .andExpect(status().isCreated()), "$.id");
    }

    private String newEntity() throws Exception {
        String acn = validAcn();
        return read(postJson("/api/entities", """
                {"name":"Custom Co %s","acn":"%s","entityType":"DEVELOPER"}""".formatted(unique(), acn))
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void createsFieldWithGeneratedApiNameAndShowsInMetadata() throws Exception {
        String label = "Preferred contact time " + unique();
        createField("person", label, "TEXT", ",\"help\":\"Morning or afternoon\",\"expected\":true,\"showInList\":true")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiName", endsWith("__c")))
                .andExpect(jsonPath("$.apiName", containsString("preferred_contact_time")))
                .andExpect(jsonPath("$.expected").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
        getJson("/api/metadata").andExpect(jsonPath("$.customFields[*].label", hasItem(label)));
        getJson("/api/metadata/custom-fields?object=person").andExpect(jsonPath("$[*].label", hasItem(label)));
    }

    @Test
    void rejectsBadDefinitions() throws Exception {
        createField("opportunity", "Nope " + unique(), "TEXT", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.object").exists());
        createField("person", "Tier " + unique(), "PICKLIST", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.picklistValues").exists());
        createField("person", "Too long " + unique(), "TEXT", ",\"maxLength\":9999")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxLength").exists());
        createField("person", "Decimals " + unique(), "NUMBER", ",\"decimalPlaces\":9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.decimalPlaces").exists());
        postJson("/api/metadata/custom-fields", "{\"object\":\"person\",\"label\":\"Bad type\",\"dataType\":\"BLOB\"}")
                .andExpect(status().isBadRequest());
        String label = "Unique label " + unique();
        createField("person", label, "TEXT", "").andExpect(status().isCreated());
        createField("person", label.toUpperCase(), "TEXT", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.label", containsString("already exists")));
    }

    @Test
    void valuesAreTypeCheckedPerFieldType() throws Exception {
        String u = unique();
        String text = read(createField("person", "Nickname " + u, "TEXT", ",\"maxLength\":5"), "$.apiName");
        String number = read(createField("person", "Kids " + u, "NUMBER", ",\"decimalPlaces\":0"), "$.apiName");
        String money = read(createField("person", "Net worth " + u, "CURRENCY", ""), "$.apiName");
        String date = read(createField("person", "Anniversary " + u, "DATE", ""), "$.apiName");
        String email = read(createField("person", "PA email " + u, "EMAIL", ""), "$.apiName");
        String phone = read(createField("person", "PA phone " + u, "PHONE", ""), "$.apiName");
        String url = read(createField("person", "Website " + u, "URL", ""), "$.apiName");
        String flag = read(createField("person", "VIP " + u, "CHECKBOX", ""), "$.apiName");
        String person = newPerson();

        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"Too long text","%s":"2.5","%s":"12.345","%s":"31-12-2026","%s":"nope",
                           "%s":"call me","%s":"no dots","%s":"yes"}}""".formatted(text, number, money, date, email, phone, url, flag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + text, containsString("at most 5")))
                .andExpect(jsonPath("$.errors." + number, containsString("whole number")))
                .andExpect(jsonPath("$.errors." + money, containsString("2 decimal")))
                .andExpect(jsonPath("$.errors." + date, containsString("YYYY-MM-DD")))
                .andExpect(jsonPath("$.errors." + email).exists())
                .andExpect(jsonPath("$.errors." + phone).exists())
                .andExpect(jsonPath("$.errors." + url).exists())
                .andExpect(jsonPath("$.errors." + flag).exists());

        // Nothing was saved when any value failed.
        getJson("/api/persons/" + person).andExpect(jsonPath("$.values." + text).doesNotExist()).andExpect(jsonPath("$.values." + flag).doesNotExist());

        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"Liv","%s":3,"%s":"1,250,000.50","%s":"2026-12-31","%s":"pa@example.test",
                           "%s":"+61 2 9000 1234","%s":"example.com.au","%s":true}}""".formatted(text, number, money, date, email, phone, url, flag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values." + text).value("Liv"))
                .andExpect(jsonPath("$.values." + number).value(3))
                .andExpect(jsonPath("$.values." + money).value(1250000.5))
                .andExpect(jsonPath("$.values." + date).value("2026-12-31"))
                .andExpect(jsonPath("$.values." + flag).value(true));

        // Partial update: only the field sent changes; null clears it.
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":null}}""".formatted(text))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values." + text).doesNotExist())
                .andExpect(jsonPath("$.values." + number).value(3));
    }

    @Test
    void picklistFieldsUseTheirOwnListAndRespectRetiredValues() throws Exception {
        ResultActions created = createField("person", "Investor tier " + unique(), "PICKLIST",
                ",\"picklistValues\":[\"Gold\",\"Silver\",\"Gold\",\" \"]").andExpect(status().isCreated());
        String api = read(created, "$.apiName");
        String set = read(created, "$.picklist");
        getJson("/api/metadata").andExpect(jsonPath("$.picklists." + set + ".values", hasSize(2)));

        String multi = read(createField("person", "Interests " + unique(), "MULTI_PICKLIST",
                ",\"picklistValues\":[\"Golf\",\"Wine\"]"), "$.apiName");
        String person = newPerson();
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"GOLD","%s":["GOLF","WINE","GOLF"]}}""".formatted(api, multi))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values." + api).value("GOLD"))
                .andExpect(jsonPath("$.values." + multi, hasSize(2)));
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"PLATINUM","%s":["CHESS"]}}""".formatted(api, multi))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + api).exists())
                .andExpect(jsonPath("$.errors." + multi).exists());

        // Retire GOLD: the record keeps it, but no record can newly choose it.
        putJson("/api/metadata/picklists/" + set + "/values/GOLD", "{\"active\":false}").andExpect(status().isNoContent());
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"GOLD"}}""".formatted(api)).andExpect(status().isOk());
        putCurrent("/api/persons/" + newPerson() + "/custom-fields", """
                {"values":{"%s":"GOLD"}}""".formatted(api))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + api, containsString("retired")));
    }

    @Test
    void unknownAndRetiredFieldsCannotBeWritten() throws Exception {
        String api = read(createField("entity", "Credit rating " + unique(), "TEXT", ""), "$.apiName");
        String fieldId = read(getJson("/api/metadata/custom-fields?object=entity"), "$[?(@.apiName == '" + api + "')].id")
                .replaceAll("[\\[\\]\"]", "");
        String entity = newEntity();
        putCurrent("/api/entities/" + entity + "/custom-fields", "{\"values\":{\"made_up__c\":\"x\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.made_up__c").exists());
        putCurrent("/api/entities/" + entity + "/custom-fields", """
                {"values":{"%s":"AA"}}""".formatted(api)).andExpect(status().isOk());

        putJson("/api/metadata/custom-fields/" + fieldId + "/active", "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        putCurrent("/api/entities/" + entity + "/custom-fields", """
                {"values":{"%s":"BB"}}""".formatted(api))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + api, containsString("retired")));
        // The stored value survives retirement (hidden while retired) and comes back on reactivation.
        getJson("/api/entities/" + entity).andExpect(jsonPath("$.values." + api).doesNotExist());
        putJson("/api/metadata/custom-fields/" + fieldId + "/active", "{\"active\":true}").andExpect(status().isOk());
        getJson("/api/entities/" + entity).andExpect(jsonPath("$.values." + api).value("AA"));
        putCurrent("/api/entities/" + entity + "/custom-fields", """
                {"values":{"%s":"BB"}}""".formatted(api)).andExpect(status().isOk());
    }

    @Test
    void editKeepsTypeAndUsesOptimisticLocking() throws Exception {
        ResultActions created = createField("entity", "Region " + unique(), "TEXT", "");
        String id = read(created, "$.id");
        String api = read(created, "$.apiName");
        putJson("/api/metadata/custom-fields/" + id, """
                {"label":"Sales region %s","help":"Where the client sits","expected":true,"showInList":true,"version":0}""".formatted(unique()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiName").value(api))
                .andExpect(jsonPath("$.dataType").value("TEXT"))
                .andExpect(jsonPath("$.showInList").value(true))
                .andExpect(jsonPath("$.version").value(1));
        // A second editor still holding version 0 is told the field changed.
        putJson("/api/metadata/custom-fields/" + id, """
                {"label":"Stale edit","version":0}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale"));
    }

    @Test
    void fieldsCanBeReordered() throws Exception {
        String u = unique();
        String first = read(createField("entity", "Order A " + u, "TEXT", ""), "$.id");
        String second = read(createField("entity", "Order B " + u, "TEXT", ""), "$.id");
        ResultActions moved = putJson("/api/metadata/custom-fields/" + second + "/move", "{\"direction\":-1}")
                .andExpect(status().isOk());
        List<String> ids = com.jayway.jsonpath.JsonPath.read(moved.andReturn().getResponse().getContentAsString(), "$[*].id");
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first));
    }

    @Test
    void customValuesCanBeSetWhenCreatingRecords() throws Exception {
        String u = unique();
        String tier = read(createField("person", "Create tier " + u, "PICKLIST", ",\"picklistValues\":[\"Gold\",\"Silver\"]"), "$.apiName");
        String worth = read(createField("person", "Create worth " + u, "CURRENCY", ",\"decimalPlaces\":0"), "$.apiName");
        String rating = read(createField("entity", "Create rating " + u, "TEXT", ""), "$.apiName");

        postJson("/api/persons", """
                {"firstName":"Born","lastName":"WithFields%s","personTypes":["INVESTOR"],%s,
                 "customFields":{"%s":"GOLD","%s":"2,000,000"}}""".formatted(u, requiredPerson(), tier, worth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values." + tier).value("GOLD"))
                .andExpect(jsonPath("$.values." + worth).value(2000000));

        postJson("/api/entities", """
                {"name":"Born With Fields %s","acn":"%s","entityType":"DEVELOPER","customFields":{"%s":"AA"}}""".formatted(u, validAcn(), rating))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values." + rating).value("AA"));
    }

    @Test
    void invalidCustomValueStopsTheWholeCreate() throws Exception {
        String u = unique();
        String worth = read(createField("person", "Strict worth " + u, "CURRENCY", ",\"decimalPlaces\":0"), "$.apiName");
        postJson("/api/persons", """
                {"firstName":"Never","lastName":"Saved%s","personTypes":["INVESTOR"],%s,"customFields":{"%s":"12.5"}}""".formatted(u, requiredPerson(), worth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + worth, containsString("whole number")));
        // Nothing was created: the Person insert rolled back with the bad value.
        postJson("/api/persons/search", "{\"q\":\"Saved" + u + "\"}").andExpect(jsonPath("$.totalElements").value(0));

        String acn = validAcn();
        postJson("/api/entities", """
                {"name":"Never Saved %s","acn":"%s","entityType":"DEVELOPER","customFields":{"nope__c":"x"}}""".formatted(u, acn))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nope__c").exists());
        // The ACN is still free, so the rollback really undid the insert.
        postJson("/api/entities", """
                {"name":"Saved Now %s","acn":"%s","entityType":"DEVELOPER"}""".formatted(u, acn))
                .andExpect(status().isCreated());
    }

    @Test
    void listsCarryValuesAndChangesAreAudited() throws Exception {
        String api = read(createField("person", "Segment " + unique(), "TEXT", ",\"showInList\":true"), "$.apiName");
        String person = newPerson();
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"Retail"}}""".formatted(api)).andExpect(status().isOk());
        postJson("/api/persons/search", """
                {"conditions":[{"field":"%s","operator":"EQUALS","value":"Retail"}],"columns":["%s"]}""".formatted(api, api))
                .andExpect(jsonPath("$.items[?(@.id == '" + person + "')].values." + api, hasItem("Retail")));
        // Sending the same value again changes nothing and writes no audit row.
        putCurrent("/api/persons/" + person + "/custom-fields", """
                {"values":{"%s":"Retail"}}""".formatted(api)).andExpect(status().isOk());
        List<String> actions = jdbc.sql("SELECT action FROM audit_event WHERE record_id = CAST(:id AS uuid) ORDER BY id")
                .param("id", person).query(String.class).list();
        assertThat(actions).containsExactly("create", "update-custom-fields");
    }
}
