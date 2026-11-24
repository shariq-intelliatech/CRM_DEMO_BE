package com.privateinvest.crm.party;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * What the production seed adds on top of the field map: the design's Sales tracking, Lending appetite and Entity
 * registry fields, the CHANGED_ON stamp, section order, and the sample records (Liquibase context dev).
 */
class DesignFieldsApiTest extends ApiTestBase {

    private static final String JAMES = "10000000-0000-4000-8000-000000000001";
    private static final String HARBOURVIEW = "20000000-0000-4000-8000-000000000001";

    @Test
    void sampleRecordsFromTheDesignAreSeeded() throws Exception {
        getJson("/api/persons/" + JAMES)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("James Caldwell"))
                .andExpect(jsonPath("$.values.salesStage").value("PROSPECT"))
                .andExpect(jsonPath("$.links[0].entityName").value("Harbourview Group Limited"))
                .andExpect(jsonPath("$.inheritedEntityType").value("DEVELOPER"));
        getJson("/api/entities/" + HARBOURVIEW)
                .andExpect(jsonPath("$.values.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.values.countryOfIncorporation").value("Australia"))
                .andExpect(jsonPath("$.persons[*].personName", hasItem("James Caldwell")));
    }

    @Test
    void designFieldsAndSectionsFollowTheDesign() throws Exception {
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[0].section").value("Sales tracking"))
                .andExpect(jsonPath("$[?(@.apiName == 'appetiteCommercial')].displayRules.visibleWhen[0].value[0]").value(hasItem("INVESTOR")))
                .andExpect(jsonPath("$[?(@.apiName == 'salesStageChangedOn')].formula.fn").value(hasItem("CHANGED_ON")));
        getJson("/api/metadata/objects/entity/fields")
                .andExpect(jsonPath("$[?(@.apiName == 'kycStatus')].section").value(hasItem("Registry, compliance and addresses")));
    }

    @Test
    void changingTheSalesStageStampsTheDate() throws Exception {
        String id = read(postJson("/api/persons", personBody("Stage", "Stamp" + unique(), "")).andExpect(status().isCreated()), "$.id");
        mvc.perform(patch("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"salesStage\":\"ACTIVE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.salesStageChangedOn").value(LocalDate.now(ZoneId.of("Australia/Sydney")).toString()));
        // The stamp is the CRM's: it can't be typed in.
        mvc.perform(patch("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"salesStageChangedOn\":\"2020-01-01\"}}"))
                .andExpect(status().isBadRequest());
    }
}
