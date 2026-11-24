package com.privateinvest.crm.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Persisted sections: section-first creation, rename, independent field ordering, section ordering, and safe deletion. */
class CustomFieldSectionApiTest extends ApiTestBase {

    @Test
    void sectionsAreBackfilledAndCanBeCreatedAndRenamed() throws Exception {
        getJson("/api/metadata/objects/person/sections")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Classification")));
        getJson("/api/metadata/objects/entity/sections")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Entity details")));

        String u = unique();
        String secName = "Compliance " + u;
        ResultActions created = postJson("/api/metadata/custom-field-sections", """
                {"object":"person","name":"%s","description":"KYC checks"}""".formatted(secName))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(secName))
                .andExpect(jsonPath("$.fieldCount").value(0));
        String secId = read(created, "$.id");
        String version = read(created, "$.version");

        // Duplicate name on the same object is rejected; standard user cannot create sections.
        postJson("/api/metadata/custom-field-sections", """
                {"object":"person","name":"%s"}""".formatted(secName.toUpperCase()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("already exists")));
        postAs("u2", "/api/metadata/custom-field-sections", """
                {"object":"person","name":"Nope %s"}""".formatted(u))
                .andExpect(status().isForbidden());

        // Creating a field with an unknown section is rejected; creating inside the new section succeeds.
        postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"Unknown Sec %s","dataType":"TEXT","section":"Does Not Exist %s"}""".formatted(u, u))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.section", containsString("existing section")));
        String fieldApi = read(postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"AML Checked %s","dataType":"CHECKBOX","section":"%s"}""".formatted(u, secName))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.section").value(secName)), "$.apiName");

        // Renaming the section updates its fields too.
        String renamed = "Regulatory Compliance " + u;
        putJson("/api/metadata/custom-field-sections/" + secId, """
                {"name":"%s","description":"Updated","version":%s}""".formatted(renamed, version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed))
                .andExpect(jsonPath("$.fieldCount").value(1));
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[?(@.apiName == '" + fieldApi + "')].section", hasItem(renamed)));
    }

    @Test
    void reorderingSectionsMovesTheirFieldsTogetherAndFieldsKeepSectionOrder() throws Exception {
        String u = unique();
        String secA = "Block A " + u;
        String secB = "Block B " + u;
        String secAId = read(postJson("/api/metadata/custom-field-sections", """
                {"object":"entity","name":"%s"}""".formatted(secA)).andExpect(status().isCreated()), "$.id");
        String secBId = read(postJson("/api/metadata/custom-field-sections", """
                {"object":"entity","name":"%s"}""".formatted(secB)).andExpect(status().isCreated()), "$.id");

        String a1 = read(postJson("/api/metadata/custom-fields", """
                {"object":"entity","label":"A1 %s","dataType":"TEXT","section":"%s"}""".formatted(u, secA))
                .andExpect(status().isCreated()), "$.id");
        String b1 = read(postJson("/api/metadata/custom-fields", """
                {"object":"entity","label":"B1 %s","dataType":"TEXT","section":"%s"}""".formatted(u, secB))
                .andExpect(status().isCreated()), "$.id");
        String b2 = read(postJson("/api/metadata/custom-fields", """
                {"object":"entity","label":"B2 %s","dataType":"TEXT","section":"%s"}""".formatted(u, secB))
                .andExpect(status().isCreated()), "$.id");

        // Move B2 before B1 independently inside section B.
        putJson("/api/metadata/custom-fields/" + b2 + "/move", "{\"direction\":-1}")
                .andExpect(status().isOk());

        // Move section B before section A.
        List<String> currentSecIds = new ArrayList<>(JsonPath.read(
                getJson("/api/metadata/objects/entity/sections").andReturn().getResponse().getContentAsString(),
                "$[*].id"));
        currentSecIds.remove(secBId);
        int aIdx = currentSecIds.indexOf(secAId);
        currentSecIds.add(aIdx, secBId);
        putJson("/api/metadata/custom-field-sections/order", """
                {"object":"entity","sectionIds":%s}""".formatted(toJsonArray(currentSecIds)))
                .andExpect(status().isOk());

        // Both B fields now appear before A1, and B2 stays before B1.
        List<String> fieldIds = JsonPath.read(
                getJson("/api/metadata/objects/entity/fields").andReturn().getResponse().getContentAsString(),
                "$[*].id");
        assertThat(fieldIds.indexOf(b2)).isLessThan(fieldIds.indexOf(b1));
        assertThat(fieldIds.indexOf(b1)).isLessThan(fieldIds.indexOf(a1));
    }

    @Test
    void deletingSectionMovesFieldsWhenNonEmptyAndDeletesEmptyDirectly() throws Exception {
        String u = unique();
        String srcId = read(postJson("/api/metadata/custom-field-sections", """
                {"object":"entity","name":"Temp Section %s"}""".formatted(u)).andExpect(status().isCreated()), "$.id");
        String destName = "Keep Section " + u;
        String destId = read(postJson("/api/metadata/custom-field-sections", """
                {"object":"entity","name":"%s"}""".formatted(destName)).andExpect(status().isCreated()), "$.id");
        String emptyName = "Empty Section " + u;
        String emptyId = read(postJson("/api/metadata/custom-field-sections", """
                {"object":"entity","name":"%s"}""".formatted(emptyName)).andExpect(status().isCreated()), "$.id");

        String fieldApi = read(postJson("/api/metadata/custom-fields", """
                {"object":"entity","label":"Moved Field %s","dataType":"TEXT","section":"Temp Section %s"}""".formatted(u, u))
                .andExpect(status().isCreated()), "$.apiName");

        // Deleting non-empty section without moveTo returns 409.
        mvc.perform(delete("/api/metadata/custom-field-sections/" + srcId).header("If-Match", "0"))
                .andExpect(status().isConflict());

        // Deleting with moveTo moves the field into destName and removes srcId.
        mvc.perform(delete("/api/metadata/custom-field-sections/" + srcId + "?moveTo=" + destId).header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldsMoved").value(1))
                .andExpect(jsonPath("$.movedTo").value(destName));
        getJson("/api/metadata/objects/entity/fields")
                .andExpect(jsonPath("$[?(@.apiName == '" + fieldApi + "')].section", hasItem(destName)));

        // Deleting empty section directly succeeds.
        mvc.perform(delete("/api/metadata/custom-field-sections/" + emptyId).header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldsMoved").value(0));
        getJson("/api/metadata/objects/entity/sections")
                .andExpect(jsonPath("$[*].id", not(hasItem(emptyId))));
    }

    private static String toJsonArray(List<String> values) {
        return "[\"" + String.join("\",\"", values) + "\"]";
    }
}
