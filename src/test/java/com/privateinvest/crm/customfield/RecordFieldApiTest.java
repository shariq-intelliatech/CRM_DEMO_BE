package com.privateinvest.crm.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** One-off fields: a labelled value on one Person or Entity only. */
class RecordFieldApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    private String newPerson(String extra) throws Exception {
        return read(postJson("/api/persons", """
                {"firstName":"One","lastName":"Off%s","personTypes":["BORROWER"],%s%s}""".formatted(unique(), requiredPerson(), extra))
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void addEditAndRemoveOnOnePersonOnly() throws Exception {
        String olivia = newPerson("");
        String other = newPerson("");
        String fieldId = read(postJson("/api/persons/" + olivia + "/record-fields", """
                {"label":"Preferred lawyer","dataType":"TEXT","value":"Calder & Moss"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFields", hasSize(1)))
                .andExpect(jsonPath("$.recordFields[0].label").value("Preferred lawyer"))
                .andExpect(jsonPath("$.recordFields[0].value").value("Calder & Moss")), "$.recordFields[0].id");

        // The other Person doesn't get it, and can use the same label for its own field.
        getJson("/api/persons/" + other).andExpect(jsonPath("$.recordFields", hasSize(0)));
        postJson("/api/persons/" + other + "/record-fields", """
                {"label":"Preferred lawyer","dataType":"TEXT","value":"Someone else"}""").andExpect(status().isOk());

        putJson("/api/persons/" + olivia + "/record-fields/" + fieldId, """
                {"label":"Preferred law firm","value":"Calder & Moss Lawyers","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFields[0].label").value("Preferred law firm"))
                .andExpect(jsonPath("$.recordFields[0].version").value(1));
        // A stale version can't overwrite a newer edit.
        putJson("/api/persons/" + olivia + "/record-fields/" + fieldId, """
                {"label":"Stale","value":"x","version":0}""")
                .andExpect(status().isConflict());

        mvc.perform(MockMvcRequestBuilders.delete("/api/persons/" + olivia + "/record-fields/" + fieldId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFields", hasSize(0)));
        // Archived, not deleted.
        assertThat(jdbc.sql("SELECT record_fields -> :f ->> 'removedAt' IS NOT NULL FROM person WHERE id = CAST(:id AS uuid)")
                .param("f", fieldId).param("id", olivia).query(Boolean.class).single()).isTrue();
        List<String> actions = jdbc.sql("SELECT action FROM audit_event WHERE record_id = CAST(:id AS uuid) ORDER BY id")
                .param("id", olivia).query(String.class).list();
        assertThat(actions).containsExactly("create", "add-record-field", "update-record-field", "remove-record-field");
    }

    @Test
    void valuesFollowTheirType() throws Exception {
        String p = newPerson("");
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Settlement target","dataType":"CURRENCY","value":"2,750,000.50"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFields[0].value").value(2750000.5));
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Owns SMSF","dataType":"BOOLEAN","value":false}""")
                .andExpect(jsonPath("$.recordFields[1].value").value(false));
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Birthday dinner","dataType":"DATE","value":"2027-02-14"}""")
                .andExpect(jsonPath("$.recordFields[2].value").value("2027-02-14"));
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Bad number","dataType":"NUMBER","value":"twelve"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.value").value("Enter a number."));
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Empty","dataType":"TEXT","value":"  "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.value", containsString("Enter a value")));
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"A list","dataType":"PICKLIST","value":"X"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dataType").exists());
    }

    @Test
    void labelsMustBeUniqueAndNotClashWithSharedFields() throws Exception {
        String p = newPerson("");
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"Nickname","dataType":"TEXT","value":"Liv"}""").andExpect(status().isOk());
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"NICKNAME","dataType":"TEXT","value":"Again"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.label", containsString("already has")));

        String shared = "Shared label " + unique();
        postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"%s","dataType":"TEXT"}""".formatted(shared)).andExpect(status().isCreated());
        postJson("/api/persons/" + p + "/record-fields", """
                {"label":"%s","dataType":"TEXT","value":"x"}""".formatted(shared))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.label", containsString("already a field for every person")));
    }

    @Test
    void createWithOneOffFieldsIsAllOrNothing() throws Exception {
        String id = newPerson(",\"recordFields\":[{\"label\":\"Assistant\",\"dataType\":\"TEXT\",\"value\":\"Jo\"}]");
        getJson("/api/persons/" + id).andExpect(jsonPath("$.recordFields[0].label").value("Assistant"));

        String u = unique();
        postJson("/api/persons", """
                {"firstName":"Never","lastName":"Made%s","personTypes":["BROKER"],%s,
                 "recordFields":[{"label":"Ok","dataType":"TEXT","value":"fine"},{"label":"Bad","dataType":"DATE","value":"soon"}]}""".formatted(u, requiredPerson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.value").exists());
        postJson("/api/persons/search", "{\"q\":\"Made" + u + "\"}").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void entitiesHaveOneOffFieldsToo() throws Exception {
        String entity = read(postJson("/api/entities", """
                {"values":{"name":"One Off Co %s","acn":"%s","entityType":"DEVELOPER"},
                 "recordFields":[{"label":"Site contact","dataType":"PHONE","value":"0400 123 456"}]}""".formatted(unique(), validAcn()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordFields[0].label").value("Site contact")), "$.id");
        postJson("/api/entities/" + entity + "/record-fields", """
                {"label":"Panel since","dataType":"DATE","value":"2024-07-01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordFields", hasSize(2)));
    }

    @Test
    void databaseRequiresAValueOfTheTypeAndUniqueLabels() throws Exception {
        String p = newPerson("");
        String write = "UPDATE person SET record_fields = CAST(:doc AS jsonb) WHERE id = CAST(:p AS uuid)";
        String noValue = """
                {"%s":{"label":"Empty","dataType":"TEXT","sortOrder":0,"version":0}}""".formatted(java.util.UUID.randomUUID());
        String wrongType = """
                {"%s":{"label":"Amount","dataType":"NUMBER","value":"lots","sortOrder":0,"version":0}}""".formatted(java.util.UUID.randomUUID());
        String sameLabel = """
                {"%s":{"label":"Lawyer","dataType":"TEXT","value":"A","sortOrder":0,"version":0},
                 "%s":{"label":"lawyer ","dataType":"TEXT","value":"B","sortOrder":1,"version":0}}"""
                .formatted(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        for (String doc : List.of(noValue, wrongType, sameLabel)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(write).param("doc", doc).param("p", p).update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
