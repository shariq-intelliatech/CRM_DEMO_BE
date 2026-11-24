package com.privateinvest.crm.audit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Record history and admin audit log tests: timeline ordering, entity-link inclusion,
 * sensitive-field redaction, archive visibility guards, and custom-field purge audit entries.
 */
class AuditApiTest extends ApiTestBase {

    @Test
    void recordHistoryIncludesEditsLinksAndRedactsSensitiveFields() throws Exception {
        String u = unique();
        String secretApi = read(postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"Audit Secret %s","dataType":"TEXT","sensitive":true}""".formatted(u))
                .andExpect(status().isCreated()), "$.apiName");

        String personId = read(postJson("/api/persons", personBody("History", "Person" + u, null))
                .andExpect(status().isCreated()), "$.id");

        String entityId = read(postJson("/api/entities", entityBody("History Entity " + u, validAcn(), "DEVELOPER", null))
                .andExpect(status().isCreated()), "$.id");

        mvc.perform(patch("/api/persons/" + personId)
                        .header("X-Dev-User", "u1")
                        .header("If-Match", versionOf("/api/persons/" + personId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"%s\":\"TOP-SECRET-%s\"}}".formatted(secretApi, u)))
                .andExpect(status().isOk());

        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"DECISION_MAKER"}""".formatted(personId, entityId))
                .andExpect(status().isCreated());

        // Admin sees the sensitive value unredacted and sees entity_link + update + create newest first.
        getAs("u1", "/api/persons/" + personId + "/history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].recordKind").value("entity_link"))
                .andExpect(jsonPath("$.items[0].action").value("create"))
                .andExpect(jsonPath("$.items[0].targetLabel").value("History Entity " + u))
                .andExpect(jsonPath("$.items[1].action").value("update"))
                .andExpect(jsonPath("$.items[1].changes[0].field").value(secretApi))
                .andExpect(jsonPath("$.items[1].changes[0].after").value("TOP-SECRET-" + u))
                .andExpect(jsonPath("$.items[1].changes[0].redacted").value(false))
                .andExpect(jsonPath("$.items[2].action").value("create"));

        // Standard user without CUSTOM_FIELD_SENSITIVE never receives the raw sensitive value.
        getAs("u2", "/api/persons/" + personId + "/history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].changes[0].sensitive").value(true))
                .andExpect(jsonPath("$.items[1].changes[0].redacted").value(true))
                .andExpect(jsonPath("$.items[1].changes[0].after").value("••••••"))
                .andExpect(content().string(not(containsString("TOP-SECRET-" + u))));

        // Archiving hides the history from users without PERSON_ARCHIVE, while admins still see it.
        mvc.perform(delete("/api/persons/" + personId)
                        .header("X-Dev-User", "u1")
                        .header("If-Match", versionOf("/api/persons/" + personId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"No longer active\"}"))
                .andExpect(status().isOk());

        getAs("u2", "/api/persons/" + personId + "/history")
                .andExpect(status().isNotFound());

        getAs("u1", "/api/persons/" + personId + "/history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("archive"))
                .andExpect(jsonPath("$.items[0].reason").value("No longer active"));
    }

    @Test
    void adminAuditLogRequiresPermissionAndRecordsFieldPurge() throws Exception {
        String u = unique();
        String fieldId = read(postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"Purge Aud %s","dataType":"TEXT"}""".formatted(u))
                .andExpect(status().isCreated()), "$.id");

        // Non-admin cannot access the global audit log.
        getAs("u2", "/api/audit").andExpect(status().isForbidden());

        // Soft delete, then permanent delete.
        String v1 = read(getJson("/api/metadata/objects/person/fields?includeDisabled=true"),
                "$.[?(@.id=='" + fieldId + "')].version").replaceAll("[\\[\\]]", "");
        mvc.perform(delete("/api/metadata/custom-fields/" + fieldId)
                        .header("X-Dev-User", "u1")
                        .header("If-Match", "\"" + v1 + "\""))
                .andExpect(status().isOk());

        String v2 = read(getJson("/api/metadata/custom-fields/deleted?object=person"),
                "$.[?(@.id=='" + fieldId + "')].version").replaceAll("[\\[\\]]", "");
        mvc.perform(delete("/api/metadata/custom-fields/" + fieldId + "?permanent=true")
                        .header("X-Dev-User", "u1")
                        .header("If-Match", "\"" + v2 + "\""))
                .andExpect(status().isOk());

        getAs("u1", "/api/audit?recordKind=custom_field_def&action=delete-permanently&recordId=" + fieldId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[*].targetLabel", hasItem("Purge Aud " + u)))
                .andExpect(jsonPath("$.items[0].details.valuesDeleted").value(0));
    }
}
