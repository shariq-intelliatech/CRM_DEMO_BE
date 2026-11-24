package com.privateinvest.crm.metadata;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;

/** Configuration (brief §2.2): admins relabel and retire values; system values and locked lists are protected. */
class MetadataApiTest extends ApiTestBase {

    /** Posts a new Person with the field map's required address, phone and email added to the body. */
    private org.springframework.test.web.servlet.ResultActions postPerson(String body) throws Exception {
        return postJson("/api/persons", "{" + requiredPerson() + "," + body.trim().substring(1));
    }

    @Test
    void metadataHasPersonAndEntityConfiguration() throws Exception {
        getJson("/api/metadata")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.person.labelPlural").value("Persons"))
                .andExpect(jsonPath("$.objects.entity.label").value("Entity"))
                .andExpect(jsonPath("$.picklists.PERSON_TYPE.multi").value(true))
                .andExpect(jsonPath("$.picklists.ENTITY_TYPE.values.length()", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.picklists.CONSULTANT_SUBTYPE.parentSet").value("ENTITY_TYPE"))
                .andExpect(jsonPath("$.settings.hotExpiryDays").value(30));
        getJson("/api/users").andExpect(jsonPath("$[*].id", hasItem("u1")));
        getJson("/api/me").andExpect(jsonPath("$.id").value("u1"));
    }

    @Test
    void relabelKeepsTheStoredCode() throws Exception {
        putJson("/api/metadata/picklists/SOURCE_CHANNEL/values/EVENT", "{\"label\":\"Industry event\"}")
                .andExpect(status().isNoContent());
        getJson("/api/metadata")
                .andExpect(jsonPath("$.picklists.SOURCE_CHANNEL.values[?(@.code == 'EVENT')].label", hasItem("Industry event")));
        putJson("/api/metadata/picklists/SOURCE_CHANNEL/values/EVENT", "{\"label\":\"Event\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    void systemValuesAndLockedListsAreProtected() throws Exception {
        putJson("/api/metadata/picklists/PERSON_TYPE/values/NOT_YET_KNOWN", "{\"active\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.active").exists());
        postJson("/api/metadata/picklists/RELATIONSHIP_STATUS/values", "{\"label\":\"Dormant\"}")
                .andExpect(status().isBadRequest());
        putJson("/api/metadata/picklists/RELATIONSHIP_STATUS/values/ACTIVE", "{\"arm\":\"CREDIT\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/metadata/picklists/CONSULTANT_SUBTYPE/values", "{\"label\":\"Surveyor\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parent").exists());
        putJson("/api/metadata/picklists/PERSON_TYPE/values/NOPE", "{\"label\":\"x\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void retiredValueStaysOnOldRecordsButNotNewOnes() throws Exception {
        String code = read(postJson("/api/metadata/picklists/INACTIVE_REASON/values", "{\"label\":\"Temp " + unique() + "\"}")
                .andExpect(status().isCreated()), "$.code");
        // A Person holding a value, then the value is retired.
        String channel = read(postJson("/api/metadata/picklists/ENGAGEMENT_TEMPERATURE/values", "{\"label\":\"Podcast " + unique() + "\"}")
                .andExpect(status().isCreated()), "$.code");
        String person = read(postPerson("""
                {"firstName":"Retired","lastName":"Value%s","personTypes":["BROKER"],"engagementTemperature":"%s"}""".formatted(unique(), channel))
                .andExpect(status().isCreated()), "$.id");
        putJson("/api/metadata/picklists/ENGAGEMENT_TEMPERATURE/values/" + channel, "{\"active\":false}").andExpect(status().isNoContent());

        getJson("/api/persons/" + person).andExpect(jsonPath("$.values.engagementTemperature").value(channel));
        postPerson("""
                {"firstName":"New","lastName":"Record%s","personTypes":["BROKER"],"engagementTemperature":"%s"}""".formatted(unique(), channel))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.engagementTemperature").exists());
        putJson("/api/metadata/picklists/INACTIVE_REASON/values/" + code, "{\"active\":false}").andExpect(status().isNoContent());
    }

    @Test
    void settingsAndLabelsUpdate() throws Exception {
        putJson("/api/metadata/settings", "{\"hotExpiryDays\":45}").andExpect(status().isNoContent());
        getJson("/api/metadata").andExpect(jsonPath("$.settings.hotExpiryDays").value(45));
        putJson("/api/metadata/settings", "{\"hotExpiryDays\":30}").andExpect(status().isNoContent());
        putJson("/api/metadata/settings", "{\"noSuchSetting\":1}").andExpect(status().isNotFound());

        putJson("/api/metadata/fields/person/dateOfBirth", "{\"label\":\"DOB\"}").andExpect(status().isNoContent());
        getJson("/api/metadata").andExpect(jsonPath("$.fields[?(@.apiName == 'dateOfBirth')].label", hasItem("DOB")));
        putJson("/api/metadata/fields/person/dateOfBirth", "{\"label\":\"Date of Birth\"}").andExpect(status().isNoContent());

        putJson("/api/metadata/objects/person", "{\"label\":\"Contact\",\"labelPlural\":\"Contacts\"}").andExpect(status().isNoContent());
        getJson("/api/metadata").andExpect(jsonPath("$.objects.person.labelPlural").value("Contacts"));
        putJson("/api/metadata/objects/person", "{\"label\":\"Person\",\"labelPlural\":\"Persons\"}").andExpect(status().isNoContent());
    }

    @Test
    void reordersAPicklistAndRejectsAPartialOrder() throws Exception {
        putJson("/api/metadata/picklists/SALES_STAGE/order", "{\"codes\":[\"ACTIVE\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.codes").exists());
        String first = read(getJson("/api/metadata"), "$.picklists.YES_NO.values[0].code");
        String second = read(getJson("/api/metadata"), "$.picklists.YES_NO.values[1].code");
        putJson("/api/metadata/picklists/YES_NO/order", "{\"codes\":[\"%s\",\"%s\"]}".formatted(second, first))
                .andExpect(status().isNoContent());
        getJson("/api/metadata").andExpect(jsonPath("$.picklists.YES_NO.values[0].code").value(second));
        putJson("/api/metadata/picklists/YES_NO/order", "{\"codes\":[\"%s\",\"%s\"]}".formatted(first, second))
                .andExpect(status().isNoContent());
    }

    @Test
    void relabelsAnEntityFieldInTheFieldRegistry() throws Exception {
        putJson("/api/metadata/fields/entity/countryOfIncorporation", "{\"label\":\"Country registered\"}")
                .andExpect(status().isNoContent());
        getJson("/api/metadata/objects/entity/fields")
                .andExpect(jsonPath("$[?(@.apiName == 'countryOfIncorporation')].label", hasItem("Country registered")));
        putJson("/api/metadata/fields/entity/countryOfIncorporation", "{\"label\":\"Country of incorporation\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    void setupChangesNeedFieldAdministration() throws Exception {
        putAs("u2", "/api/metadata/settings", "{\"hotExpiryDays\":1}").andExpect(status().isForbidden());
        putAs("u2", "/api/metadata/objects/person", "{\"label\":\"X\",\"labelPlural\":\"Xs\"}").andExpect(status().isForbidden());
        putAs("u2", "/api/metadata/picklists/SOURCE_CHANNEL/values/EVENT", "{\"label\":\"X\"}").andExpect(status().isForbidden());
        postAs("u2", "/api/metadata/picklists/SOURCE_CHANNEL/values", "{\"label\":\"X\"}").andExpect(status().isForbidden());
        putAs("u2", "/api/metadata/picklists/YES_NO/order", "{\"codes\":[\"YES\",\"NO\"]}").andExpect(status().isForbidden());
        getJson("/api/metadata").andExpect(jsonPath("$.settings.hotExpiryDays").value(30));
    }
}
