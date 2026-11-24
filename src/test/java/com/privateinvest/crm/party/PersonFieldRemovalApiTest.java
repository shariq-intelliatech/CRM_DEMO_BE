package com.privateinvest.crm.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Removing a field from one Person: the value goes, the field leaves that record only, and it can come back. */
class PersonFieldRemovalApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    private String person(String first, String last) throws Exception {
        return read(postJson("/api/persons", """
                {"duplicateReason":"Test data","values":{"firstName":"%s","lastName":"%s","personTypes":["BROKER"],
                 "dateOfBirth":"1980-02-02","preferredName":"%s",%s}}""".formatted(first, last, first, requiredPerson()))
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void removedFieldLeavesOnlyThatPersonAndCanComeBack() throws Exception {
        String u = unique();
        String a = person("Rem", "Ove" + u);
        String b = person("Kee", "Pit" + u);

        mvc.perform(delete("/api/persons/" + a + "/fields/dateOfBirth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenFields", hasItem("dateOfBirth")))
                .andExpect(jsonPath("$.values.dateOfBirth").doesNotExist());
        assertThat(jdbc.sql("SELECT (custom_fields -> 'dateOfBirth') IS NULL FROM person WHERE id = CAST(:id AS uuid)")
                .param("id", a).query(Boolean.class).single()).isTrue();
        // The other Person and the field itself are untouched.
        getJson("/api/persons/" + b).andExpect(jsonPath("$.values.dateOfBirth").value("1980-02-02"))
                .andExpect(jsonPath("$.hiddenFields").isEmpty());
        getJson("/api/metadata/objects/person/fields").andExpect(jsonPath("$[*].apiName", hasItem("dateOfBirth")));

        // Required fields can't be removed (the field map's Email list among them).
        mvc.perform(delete("/api/persons/" + a + "/fields/lastName")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.lastName").exists());
        mvc.perform(delete("/api/persons/" + a + "/fields/emails")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/persons/" + a + "/fields/nope")).andExpect(status().isNotFound());

        // Adding it back shows it again, empty; giving a removed field a value adds it back too.
        mvc.perform(post("/api/persons/" + a + "/fields/dateOfBirth"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hiddenFields", not(hasItem("dateOfBirth"))));
        mvc.perform(delete("/api/persons/" + a + "/fields/preferredName")).andExpect(jsonPath("$.hiddenFields", hasItem("preferredName")));
        mvc.perform(patch("/api/persons/" + a).header("If-Match", versionOf("/api/persons/" + a))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"preferredName\":\"Remy\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenFields", not(hasItem("preferredName"))))
                .andExpect(jsonPath("$.values.preferredName").value("Remy"));

        List<String> actions = jdbc.sql("SELECT action FROM audit_event WHERE record_id = CAST(:id AS uuid) ORDER BY id")
                .param("id", a).query(String.class).list();
        assertThat(actions).containsExactly("create", "remove-field", "add-field-back", "remove-field", "update");
    }
}
