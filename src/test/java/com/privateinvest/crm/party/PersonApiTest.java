package com.privateinvest.crm.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Person rules from the client field map and the brief: required fields, phone and email lists with one primary,
 * conditional fields (ABN when a sole trader, preferred phone and email, marketing), formulas, the duplicate
 * check (§3.8.3), Hot (§3.8.2), versioned saves, and the Person rules that name their fields.
 */
class PersonApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    private String createPerson(String first, String last, String extraMembers) throws Exception {
        return read(postJson("/api/persons", personBody(first, last, extraMembers)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions patchPerson(String id, String values) throws Exception {
        return mvc.perform(patch("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + values + "}"));
    }

    @Test
    void createsPersonWithPhoneAndEmailLists() throws Exception {
        String u = unique();
        String mobile = "04" + randomDigits(8);
        postJson("/api/persons", """
                {"values":{"firstName":"Ava","lastName":"Create%s","personTypes":["BROKER","INVESTOR"],
                 "street":"1 George St","addressLine2":"Level 2","suburb":"Sydney","state":"NSW","postcode":"2000",
                 "phones":[{"label":"OFFICE","value":"02 9000 %s"},{"label":"MOBILE","value":"%s","primary":true}],
                 "emails":[{"label":"BUSINESS","value":"ava.%s@example.test"}]}}""".formatted(u, randomDigits(4), mobile, u))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("person"))
                .andExpect(jsonPath("$.title").value("Ava Create" + u))
                .andExpect(jsonPath("$.values.personTypes", hasSize(2)))
                .andExpect(jsonPath("$.values.phones", hasSize(2)))
                .andExpect(jsonPath("$.values.phones[1].primary").value(true))
                .andExpect(jsonPath("$.values.phones[0].primary").value(false))
                // One email and no primary marked: it becomes the primary.
                .andExpect(jsonPath("$.values.emails[0].primary").value(true))
                .andExpect(jsonPath("$.values.ownerId").value("u1"))          // owner rule: the current user
                .andExpect(jsonPath("$.values.country").value("Australia"));  // field default
    }

    @Test
    void fieldMapRequiredFieldsAndListRulesAreChecked() throws Exception {
        postJson("/api/persons", """
                {"firstName":" ","lastName":"","personTypes":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.lastName").exists())
                .andExpect(jsonPath("$.errors.personTypes").exists())
                .andExpect(jsonPath("$.errors.street").exists())
                .andExpect(jsonPath("$.errors.suburb").exists())
                .andExpect(jsonPath("$.errors.postcode").exists())
                .andExpect(jsonPath("$.errors.phones").exists())
                .andExpect(jsonPath("$.errors.emails").exists())
                .andExpect(jsonPath("$.errors.addressLine2").exists());
        String u = unique();
        String base = "{\"values\":{\"firstName\":\"List\",\"lastName\":\"Rules" + u + "\",\"personTypes\":[\"BROKER\"],"
                + "\"street\":\"1 A St\",\"addressLine2\":\"Level 2\",\"suburb\":\"Sydney\",\"state\":\"NSW\",\"postcode\":\"2000\",";
        String oneEmail = "\"emails\":[{\"label\":\"BUSINESS\",\"value\":\"l." + u + "@example.test\"}]";
        String onePhone = "\"phones\":[{\"label\":\"MOBILE\",\"value\":\"04" + randomDigits(8) + "\"}]";
        // Two primaries.
        postJson("/api/persons", base + oneEmail + ",\"phones\":[{\"label\":\"MOBILE\",\"value\":\"0400 000 001\",\"primary\":true},"
                + "{\"label\":\"OFFICE\",\"value\":\"02 9999 0000\",\"primary\":true}]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phones", containsString("one phone number as primary")));
        // A label that isn't in the list.
        postJson("/api/persons", base + onePhone + ",\"emails\":[{\"label\":\"WORKISH\",\"value\":\"w." + u + "@example.test\"}]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.emails").exists());
        // A bad email.
        postJson("/api/persons", base + onePhone + ",\"emails\":[{\"label\":\"BUSINESS\",\"value\":\"nope@\"}]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.emails", containsString("valid format")));
        // The same number twice, written differently.
        postJson("/api/persons", base + oneEmail + ",\"phones\":[{\"label\":\"MOBILE\",\"value\":\"0400 000 002\"},"
                + "{\"label\":\"OTHER\",\"value\":\"0400000002\"}]}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phones", containsString("listed twice")));
        // Retired option codes and unknown users are refused.
        postJson("/api/persons", personBody("Bad", "Codes" + u, ",\"ownerId\":\"nobody\"")
                .replace("[\"BROKER\"]", "[\"BORROWER_OR_SPONSOR\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.personTypes").exists())
                .andExpect(jsonPath("$.errors.ownerId").exists());
        postJson("/api/persons", "{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation"));
    }

    @Test
    void abnIsRequiredAndCheckedForASoleTrader() throws Exception {
        String u = unique();
        postJson("/api/persons", personBody("Sole", "Trader" + u, ",\"soleTrader\":\"YES\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.abn").value("ABN is required."));
        postJson("/api/persons", personBody("Sole", "Trader" + u, ",\"soleTrader\":\"YES\",\"abn\":\"51 824 753\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.abn").exists());
        String acn = validAcn();
        postJson("/api/persons", personBody("Sole", "Trader" + u, ",\"soleTrader\":\"YES\",\"abn\":\"" + spaced(abnFor(acn)) + "\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values.abn").value(abnFor(acn)));   // kept as digits
        postJson("/api/persons", personBody("Not", "Trader" + unique(), ",\"soleTrader\":\"NO\""))
                .andExpect(status().isCreated());
    }

    @Test
    void preferredPhoneAndEmailPickFromTheRecordsOwnLists() throws Exception {
        String u = unique();
        String mobile = "04" + randomDigits(8);
        String email = "pref." + u + "@example.test";
        String body = """
                {"values":{"firstName":"Pref","lastName":"Channel%s","personTypes":["INVESTOR"],%s,
                 "preferredChannels":["SMS","EMAIL"]}}""".formatted(u, requiredPerson(email, mobile));
        postJson("/api/persons", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.preferredPhone").value("Preferred phone is required."))
                .andExpect(jsonPath("$.errors.preferredEmail").value("Preferred email is required."));
        postJson("/api/persons", body.replace("\"preferredChannels\"", "\"preferredPhone\":\"0499 999 999\",\"preferredEmail\":\"other@example.test\",\"preferredChannels\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.preferredPhone", containsString("phone numbers")))
                .andExpect(jsonPath("$.errors.preferredEmail", containsString("email addresses")));
        String id = read(postJson("/api/persons", body.replace("\"preferredChannels\"", "\"preferredPhone\":\""
                + mobile.substring(0, 4) + " " + mobile.substring(4) + "\",\"preferredEmail\":\"" + email.toUpperCase()
                + "\",\"preferredChannels\"")).andExpect(status().isCreated()), "$.id");
        // Removing the phone it points to is refused until another preferred phone is chosen.
        patchPerson(id, "{\"phones\":[{\"label\":\"OFFICE\",\"value\":\"02 9123 4567\",\"primary\":true}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.preferredPhone").exists());
    }

    @Test
    void marketingConsentNeedsChannelsAndStampsWhoChangedIt() throws Exception {
        String id = createPerson("Mark", "Eting" + unique(), "");
        patchPerson(id, "{\"marketingConsent\":\"OPTED_OUT\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.marketingChannels").value("Marketing channels is required."));
        patchPerson(id, "{\"marketingConsent\":\"OPTED_OUT\",\"marketingChannels\":[\"EMAIL\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.preferredMarketingEmail").exists());
        patchPerson(id, "{\"marketingConsent\":\"OPTED_OUT\",\"marketingChannels\":[\"EMAIL\"],"
                + "\"preferredMarketingEmail\":\"m@example.test\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.values.marketingUpdatedBy").value("u1"));
        // The stamp is set by the CRM, never typed; another user's change moves it.
        patchPerson(id, "{\"marketingUpdatedBy\":\"u2\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.marketingUpdatedBy", containsString("set by the CRM")));
        mvc.perform(patch("/api/persons/" + id).header("X-Dev-User", "u2").header("If-Match", versionOf("/api/persons/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"consentWithdrawnDate\":\"2026-10-01\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values.marketingUpdatedBy").value("u2"));
    }

    @Test
    void linkedinConnectAgeIsWorkedOutInWeeks() throws Exception {
        String date = LocalDate.now(java.time.ZoneId.of("Australia/Sydney")).minusDays(23).toString();
        String id = createPerson("Link", "Edin" + unique(), ",\"linkedinDate\":\"" + date + "\"");
        getJson("/api/persons/" + id).andExpect(jsonPath("$.values.linkedinAgeWeeks").value(3));
        patchPerson(id, "{\"linkedinAgeWeeks\":10}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.linkedinAgeWeeks").exists());
        postJson("/api/persons/search", "{\"q\":\"Edin\",\"columns\":[\"linkedinAgeWeeks\"]}")
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].values.linkedinAgeWeeks", hasItem(3)));
        postJson("/api/persons/search", "{\"sort\":[{\"field\":\"linkedinAgeWeeks\",\"dir\":\"ASC\"}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateCheckWarnsOnAnyEmailAnyMobileAndNameWithDob() throws Exception {
        String u = unique();
        String mobile = "04" + randomDigits(8);
        String email = "dup." + u + "@example.test";
        postJson("/api/persons", """
                {"values":{"firstName":"Dup","lastName":"Original%s","personTypes":["BROKER"],"dateOfBirth":"1980-01-02",
                 "street":"1 A St","addressLine2":"Level 2","suburb":"Sydney","state":"NSW","postcode":"2000",
                 "phones":[{"label":"MOBILE","value":"%s"}],
                 "emails":[{"label":"BUSINESS","value":"first.%s@example.test"},{"label":"PERSONAL","value":"%s"}]}}"""
                .formatted(u, mobile, u, email)).andExpect(status().isCreated());

        // The second email of the existing Person matches any email of the new one.
        postJson("/api/persons", """
                {"values":{"firstName":"Someone","lastName":"Else%s","personTypes":["BROKER"],
                 "street":"1 A St","addressLine2":"Level 2","suburb":"Sydney","state":"NSW","postcode":"2000",
                 "phones":[{"label":"MOBILE","value":"04%s"}],
                 "emails":[{"label":"BUSINESS","value":"else.%s@example.test"},{"label":"OTHER","value":"%s"}]}}"""
                .formatted(unique(), randomDigits(8), unique(), email.toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-warning"))
                .andExpect(jsonPath("$.matches[*].reasons[*]", hasItem("Same email")));
        String spacedMobile = mobile.substring(0, 4) + " " + mobile.substring(4);
        postJson("/api/persons", """
                {"values":{"firstName":"Other","lastName":"Mobile%s","personTypes":["BROKER"],%s}}"""
                .formatted(unique(), requiredPerson("m." + unique() + "@example.test", spacedMobile)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.matches[*].reasons[*]", hasItem("Same mobile")));
        // The same number as an office phone isn't a mobile, so it doesn't count.
        postJson("/api/persons", """
                {"values":{"firstName":"Office","lastName":"Line%s","personTypes":["BROKER"],%s}}"""
                .formatted(unique(), requiredPerson("o." + unique() + "@example.test", mobile).replace("MOBILE", "OFFICE")))
                .andExpect(status().isCreated());
        postJson("/api/persons", personBody("Dup", "Original" + u, ",\"dateOfBirth\":\"1980-01-02\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.matches[*].reasons[*]", hasItem("Same name and date of birth")));
        // Saving past the warning flags the record.
        postJson("/api/persons", personBody("Dup", "Original" + u, "").replace("\"duplicateReason\":null", "\"duplicateReason\":\"Different person\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flags.possibleDuplicateReason").value("Different person"));
    }

    @Test
    void hotFlagAndAuditTrail() throws Exception {
        String id = createPerson("Audit", "Trail" + unique(), "");
        putJson("/api/persons/" + id + "/hot", "{\"hot\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.hotSince").exists());
        List<String> actions = jdbc.sql("SELECT action FROM audit_event WHERE record_id = CAST(:id AS uuid) ORDER BY id")
                .param("id", id).query(String.class).list();
        assertThat(actions).containsExactly("create", "flag-hot");
        getJson("/api/persons/00000000-0000-4000-8000-000000000000").andExpect(status().isNotFound());
    }

    @Test
    void savesNeedTheLoadedVersion() throws Exception {
        String id = createPerson("Stale", "Screen" + unique(), "");
        String loaded = versionOf("/api/persons/" + id);
        patchPerson(id, "{\"dateOfBirth\":\"1990-05-05\"}").andExpect(status().isOk());
        mvc.perform(patch("/api/persons/" + id).header("If-Match", loaded).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"dateOfBirth\":\"1991-01-01\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale"));
        mvc.perform(patch("/api/persons/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"dateOfBirth\":\"1991-01-01\"}}")).andExpect(status().is(428));
        getJson("/api/persons/" + id).andExpect(jsonPath("$.values.dateOfBirth").value("1990-05-05"));
    }

    /** The rules after V1012 seeds them; restored after the test so other tests see the usual record name. */
    public static final String DEFAULT_RULES = """
            {"nameFields":["firstName","lastName"],"subtitleFields":["personTypes","emails"],
             "duplicateCheck":{"nameFields":["firstName","lastName"],"emailField":"emails","mobileField":"phones","dateOfBirthField":"dateOfBirth"},
             "uniqueFields":[],"ownerField":"ownerId","typeField":"personTypes",
             "reviewQueue":{"field":"personTypes","values":["NOT_YET_KNOWN"]},
             "defaultSort":[]}""";

    @Test
    void personRulesNameTheFieldsTheyUse() throws Exception {
        try {
            checkPersonRules();
        } finally {
            putJson("/api/metadata/objects/person/rules", DEFAULT_RULES).andExpect(status().isOk());
        }
    }

    private void checkPersonRules() throws Exception {
        String u = unique();
        String nick = read(postJson("/api/metadata/custom-fields", """
                {"object":"person","label":"Nickname %s","dataType":"TEXT"}""".formatted(u)).andExpect(status().isCreated()), "$.apiName");
        String id = createPerson("Rule", "Name" + u, ",\"" + nick + "\":\"Ruly" + u + "\"");

        // The record name can be any text fields.
        putJson("/api/metadata/objects/person/rules", DEFAULT_RULES.replace("\"nameFields\":[\"firstName\",\"lastName\"],\"subtitleFields\"",
                "\"nameFields\":[\"" + nick + "\",\"lastName\"],\"subtitleFields\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings", hasSize(0)));
        getJson("/api/persons/" + id).andExpect(jsonPath("$.title").value("Ruly" + u + " Name" + u));
        getJson("/api/persons/lookup?q=Ruly" + u).andExpect(jsonPath("$.items[0].title").value("Ruly" + u + " Name" + u));

        // Wrong types and unknown fields are refused; only field managers may change rules.
        putJson("/api/metadata/objects/person/rules", """
                {"nameFields":["dateOfBirth"],"duplicateCheck":{"emailField":"phones"},"ownerField":"nope",
                 "reviewQueue":{"field":"personTypes","values":[]}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nameFields").exists())
                .andExpect(jsonPath("$['errors']['duplicateCheck.emailField']").exists())
                .andExpect(jsonPath("$['errors']['reviewQueue.values']").exists())
                .andExpect(jsonPath("$.errors.ownerField").exists());
        mvc.perform(put("/api/metadata/objects/person/rules").header("X-Dev-User", "u2").contentType(MediaType.APPLICATION_JSON)
                .content(DEFAULT_RULES)).andExpect(status().isForbidden());
        List<String> nickId = com.jayway.jsonpath.JsonPath.read(getJson("/api/metadata/objects/person/fields")
                .andReturn().getResponse().getContentAsString(), "$[?(@.apiName == '" + nick + "')].id");
        putJson("/api/metadata/custom-fields/" + nickId.getFirst() + "/active", "{\"active\":false}").andExpect(status().isOk());

        // A disabled name field drops out of the name and Setup warns.
        getJson("/api/metadata/objects/person/rules").andExpect(jsonPath("$.warnings[0]", containsString("Record name")));
        getJson("/api/persons/" + id).andExpect(jsonPath("$.title").value("Name" + u));
    }

    @Test
    void myPersonsFollowTheOwnerRule() throws Exception {
        String u = unique();
        postAs("u3", "/api/persons", personBody("Owned", "ByThree" + u, "")).andExpect(status().isCreated());
        postAs("u3", "/api/persons/search", "{\"ownerScope\":\"MINE\",\"q\":\"ByThree" + u + "\"}")
                .andExpect(jsonPath("$.totalElements").value(1));
        postAs("u2", "/api/persons/search", "{\"ownerScope\":\"MINE\",\"q\":\"ByThree" + u + "\"}")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void phoneAndEmailListsAreSearchedFilteredAndSorted() throws Exception {
        String u = unique();
        String mobile = "04" + randomDigits(8);
        String id = read(postJson("/api/persons", """
                {"values":{"firstName":"Find","lastName":"Lists%s","personTypes":["BROKER"],%s}}"""
                .formatted(u, requiredPerson("zz.find." + u + "@example.test", mobile))).andExpect(status().isCreated()), "$.id");
        postJson("/api/persons/search", "{\"q\":\"zz.find." + u + "\"}").andExpect(jsonPath("$.items[0].id").value(id));
        postJson("/api/persons/search", "{\"q\":\"" + mobile.substring(2) + "\"}").andExpect(jsonPath("$.items[*].id", hasItem(id)));
        postJson("/api/persons/search", """
                {"conditions":[{"field":"emails","operator":"STARTS_WITH","value":"zz.find.%s"},
                               {"field":"phones","operator":"EQUALS","value":"%s"}],"logic":"1 AND 2"}""".formatted(u, mobile))
                .andExpect(jsonPath("$.totalElements").value(1));
        postJson("/api/persons/search", "{\"q\":\"Lists" + u + "\",\"sort\":[{\"field\":\"emails\",\"dir\":\"DESC\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void defaultSearchOrderShowsNewestCreatedPersonFirst() throws Exception {
        String u = unique();
        postJson("/api/persons", """
                {"values":{"firstName":"Aaron","lastName":"First%s","personTypes":["BROKER"],%s}}"""
                .formatted(u, requiredPerson("aaron." + u + "@example.test", "04" + randomDigits(8))))
                .andExpect(status().isCreated());
        String newestId = read(postJson("/api/persons", """
                {"values":{"firstName":"Zara","lastName":"Newest%s","personTypes":["BROKER"],%s}}"""
                .formatted(u, requiredPerson("zara." + u + "@example.test", "04" + randomDigits(8))))
                .andExpect(status().isCreated()), "$.id");
        postJson("/api/persons/search", "{}").andExpect(jsonPath("$.items[0].id").value(newestId));
    }

    private static String spaced(String digits) {
        return digits.substring(0, 2) + " " + digits.substring(2, 5) + " " + digits.substring(5, 8) + " " + digits.substring(8);
    }
}
