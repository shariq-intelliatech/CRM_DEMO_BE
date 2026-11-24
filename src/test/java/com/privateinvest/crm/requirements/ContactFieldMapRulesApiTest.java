package com.privateinvest.crm.requirements;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The behaviour each field map row asks for, end to end through the Person API: the "Field rule" and "Field notes"
 * columns, the picklist values, and the hard gates of the brief they rely on (§2.4 G2: a wrong format names the
 * field and stops the save). The field definitions themselves are checked by {@link ContactFieldMapConformanceTest}.
 */
@DisplayName("Client field map (Contact) rules")
class ContactFieldMapRulesApiTest extends ApiTestBase {

    private ResultActions createWith(String extraMembers) throws Exception {
        return postJson("/api/persons", personBody("Rule", "Check" + unique(), extraMembers));
    }

    private String create(String extraMembers) throws Exception {
        return read(createWith(extraMembers).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions patchPerson(String id, String values) throws Exception {
        return mvc.perform(patch("/api/persons/" + id).header("If-Match", versionOf("/api/persons/" + id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + values + "}"));
    }

    /** A Person body with the given phones and emails JSON arrays instead of the defaults. */
    private static String bodyWithLists(String phones, String emails) {
        return """
                {"values":{"firstName":"List","lastName":"Primary%s","personTypes":["BROKER"],
                 "street":"1 Test Street","addressLine2":"Level 2","suburb":"Sydney","state":"NSW","postcode":"2000","country":"Australia",
                 "phones":%s,"emails":%s}}""".formatted(unique(), phones, emails);
    }

    @Nested
    @DisplayName("Classification block")
    class Classification {

        @Test
        @DisplayName("Type of contact holds more than one value, from the client's list")
        void typeOfContactIsMultiSelect() throws Exception {
            postJson("/api/persons", personBody("Multi", "Type" + unique(), "")
                    .replace("[\"BROKER\"]", "[\"BROKER\",\"BORROWER\",\"INVESTOR\",\"SERVICING_CLIENT\"]"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.personTypes",
                            containsInAnyOrder("BROKER", "BORROWER", "INVESTOR", "SERVICING_CLIENT")));
            postJson("/api/persons", personBody("Bad", "Type" + unique(), "").replace("[\"BROKER\"]", "[\"ASTRONAUT\"]"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.personTypes").exists());
        }

        @Test
        @DisplayName("Not yet known puts the Person on the Person Type review queue (brief §3.2)")
        void notYetKnownGoesOnTheReviewQueue() throws Exception {
            // Different name fragments, so the similar-name duplicate warning does not fire between the two.
            String waiting = unique();
            String classified = unique();
            String queued = read(postJson("/api/persons", personBody("Queue", "Waiting" + waiting, "")
                    .replace("[\"BROKER\"]", "[\"NOT_YET_KNOWN\"]")).andExpect(status().isCreated()), "$.id");
            String known = read(postJson("/api/persons", personBody("Known", "Broker" + classified, ""))
                    .andExpect(status().isCreated()), "$.id");
            String queue = """
                    {"q":"%s","conditions":[{"field":"personTypes","operator":"CONTAINS_ANY","value":["NOT_YET_KNOWN"]}],"size":100}""";
            postJson("/api/persons/search", queue.formatted(waiting)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].id", hasItem(queued)));
            postJson("/api/persons/search", queue.formatted(classified)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].id", not(hasItem(known))));
            getJson("/api/metadata/objects/person/rules")
                    .andExpect(jsonPath("$.rules.reviewQueue.field").value("personTypes"))
                    .andExpect(jsonPath("$.rules.reviewQueue.values", hasItem("NOT_YET_KNOWN")));
        }

        @Test
        @DisplayName("Type of investor is single-select: Private Wealth")
        void typeOfInvestorIsSingleSelect() throws Exception {
            createWith(",\"investorSubType\":\"PRIVATE_WEALTH\"").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.investorSubType").value("PRIVATE_WEALTH"));
            createWith(",\"investorSubType\":[\"PRIVATE_WEALTH\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.investorSubType").exists());
        }

        @Test
        @DisplayName("Type of service provider holds several of the client's six values")
        void typeOfServiceProviderIsMultiSelect() throws Exception {
            createWith(",\"serviceProviderTypes\":[\"CONSTRUCTION\",\"LEGAL_COMPLIANCE\",\"PLANNING_ENVIRONMENT\"]")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.serviceProviderTypes", hasSize(3)));
            createWith(",\"serviceProviderTypes\":[\"PLUMBING\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.serviceProviderTypes").exists());
        }
    }

    @Nested
    @DisplayName("Personal details block")
    class PersonalDetails {

        @ParameterizedTest(name = "{0} is required")
        @ValueSource(strings = {"firstName", "lastName", "street", "addressLine2", "suburb", "state", "postcode", "phones", "emails"})
        @DisplayName("each required Personal details field stops the save when it is cleared")
        void requiredFieldCannotBeCleared(String field) throws Exception {
            String id = create("");
            patchPerson(id, "{\"" + field + "\":null}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + field).exists());
        }

        @Test
        @DisplayName("Country is required and defaults to Australia")
        void countryDefaultsAndIsRequired() throws Exception {
            String body = personBody("No", "Country" + unique(), "").replace(",\"country\":\"Australia\"", "");
            String id = read(postJson("/api/persons", body).andExpect(status().isCreated()), "$.id");
            getJson("/api/persons/" + id).andExpect(jsonPath("$.values.country").value("Australia"));
            patchPerson(id, "{\"country\":\"\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.country").exists());
        }

        @Test
        @DisplayName("Phones: several records, and exactly one primary (the first when none is marked)")
        void phonesNeedOnePrimary() throws Exception {
            String email = "[{\"label\":\"BUSINESS\",\"value\":\"ph." + unique() + "@example.test\"}]";
            postJson("/api/persons", bodyWithLists("""
                    [{"label":"OFFICE","value":"02 9%s"},{"label":"MOBILE","value":"04%s"},{"label":"HOME","value":"02 8%s"}]"""
                    .formatted(randomDigits(7), randomDigits(8), randomDigits(7)), email))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.phones", hasSize(3)))
                    .andExpect(jsonPath("$.values.phones[?(@.primary == true)]", hasSize(1)))
                    .andExpect(jsonPath("$.values.phones[0].primary").value(true));
        }

        @Test
        @DisplayName("Email: several records, and exactly one primary")
        void emailsNeedOnePrimary() throws Exception {
            String u = unique();
            String phone = "[{\"label\":\"MOBILE\",\"value\":\"04" + randomDigits(8) + "\"}]";
            postJson("/api/persons", bodyWithLists(phone, """
                    [{"label":"BUSINESS","value":"a.%s@example.test"},{"label":"BUSINESS_2","value":"b.%s@example.test","primary":true},
                     {"label":"PERSONAL","value":"c.%s@example.test"}]""".formatted(u, u, u)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.emails[?(@.primary == true)]", hasSize(1)))
                    .andExpect(jsonPath("$.values.emails[1].primary").value(true));
            postJson("/api/persons", bodyWithLists(phone, """
                    [{"label":"BUSINESS","value":"d.%s@example.test","primary":true},{"label":"OTHER","value":"e.%s@example.test","primary":true}]"""
                    .formatted(u, u)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.emails").exists());
        }

        @ParameterizedTest(name = "phone label {0}")
        @ValueSource(strings = {"OFFICE", "BUSINESS_2", "MOBILE", "HOME", "OTHER"})
        @DisplayName("every phone label of the field map is accepted")
        void everyPhoneLabelIsAccepted(String label) throws Exception {
            postJson("/api/persons", bodyWithLists("[{\"label\":\"" + label + "\",\"value\":\"02 9" + randomDigits(7) + "\"}]",
                    "[{\"label\":\"BUSINESS\",\"value\":\"lbl." + unique() + "@example.test\"}]"))
                    .andExpect(status().isCreated());
        }

        @ParameterizedTest(name = "email label {0}")
        @ValueSource(strings = {"BUSINESS", "BUSINESS_2", "PERSONAL", "OTHER"})
        @DisplayName("every email label of the field map is accepted")
        void everyEmailLabelIsAccepted(String label) throws Exception {
            postJson("/api/persons", bodyWithLists("[{\"label\":\"MOBILE\",\"value\":\"04" + randomDigits(8) + "\"}]",
                    "[{\"label\":\"" + label + "\",\"value\":\"lbl." + unique() + "@example.test\"}]"))
                    .andExpect(status().isCreated());
        }

        @ParameterizedTest(name = "date of birth \"{0}\" is refused")
        @ValueSource(strings = {"31/12/1980", "1980-13-01", "1980-02-30", "yesterday"})
        @DisplayName("a wrong date of birth stops the save and names the field (G2)")
        void wrongDateOfBirthIsRefused(String dob) throws Exception {
            createWith(",\"dateOfBirth\":\"" + dob + "\"").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.dateOfBirth", containsString("YYYY-MM-DD")));
        }

        @ParameterizedTest(name = "{0} = \"{1}\" is refused")
        @CsvSource({"linkedinUrl, not a url", "linkedinUrl, linkedin", "webpage, http://exa mple.com", "webpage, nodot"})
        @DisplayName("LinkedIn profile and Webpage must be web addresses (G2)")
        void webAddressesAreChecked(String field, String value) throws Exception {
            createWith(",\"" + field + "\":\"" + value + "\"").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + field).exists());
        }

        @Test
        @DisplayName("LinkedIn profile and Webpage keep a valid web address")
        void validWebAddressesAreKept() throws Exception {
            createWith(",\"linkedinUrl\":\"https://www.linkedin.com/in/someone\",\"webpage\":\"example.com.au\"")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.linkedinUrl").value("https://www.linkedin.com/in/someone"))
                    .andExpect(jsonPath("$.values.webpage").value("example.com.au"));
        }
    }

    @Nested
    @DisplayName("Legal details block")
    class LegalDetails {

        @Test
        @DisplayName("ABN is not asked for when Sole trader is No or empty")
        void abnNotRequiredUnlessSoleTrader() throws Exception {
            createWith("").andExpect(status().isCreated());
            createWith(",\"soleTrader\":\"NO\",\"tradingName\":\"Acme Trading\"").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.tradingName").value("Acme Trading"));
        }

        @Test
        @DisplayName("making a saved Person a sole trader then needs a valid ABN")
        void becomingASoleTraderNeedsAnAbn() throws Exception {
            String id = create("");
            patchPerson(id, "{\"soleTrader\":\"YES\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.abn").value("ABN is required."));
            patchPerson(id, "{\"soleTrader\":\"YES\",\"abn\":\"51824753557\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.abn", containsString("valid ABN")));
            patchPerson(id, "{\"soleTrader\":\"YES\",\"abn\":\"51 824 753 556\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.values.abn").value("51824753556"));
        }
    }

    @Nested
    @DisplayName("Contact and Investor profile blocks")
    class Profiles {

        @Test
        @DisplayName("Background holds 32,000 characters in full (brief §1.2 problem 4)")
        void backgroundHoldsLongText() throws Exception {
            String text = "x".repeat(31_990) + " end-check";
            String id = create(",\"background\":\"" + text + "\"");
            getJson("/api/persons/" + id).andExpect(jsonPath("$.values.background").value(text));
            patchPerson(id, "{\"background\":\"" + "y".repeat(32_001) + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.background", containsString("32000")));
        }

        @Test
        @DisplayName("Contact Source and Database source hold several values; values with commas keep them")
        void sourcesAreMultiSelect() throws Exception {
            createWith(",\"contactSources\":[\"LENDFORGOOD\",\"PRIVATEINVEST_INSIGHTS\",\"OTHER\"],"
                    + "\"databaseSources\":[\"FINANCE_BROKER_PROJECT_MONEYBALL\",\"INTRODUCER_ETHIUM\"]")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.contactSources", hasSize(3)))
                    .andExpect(jsonPath("$.values.databaseSources", hasSize(2)));
        }

        @Test
        @DisplayName("Investor profile and Fund registry details are stored for an investor")
        void investorProfileIsStored() throws Exception {
            postJson("/api/persons", personBody("Inv", "Profile" + unique(), """
                    ,"engagementStyle":"Prefers quarterly calls","preferredOfferings":["PI_FIRST_MORTGAGE_FUND","MANAGING_CREDIT_BOOKS"],
                    "loanSectors":["RESIDENTIAL","MIXED_USE"],"wealthIndustry":["FINTECH","PROPERTY","WINE"],
                    "holderId":"X0012345678","registrationDetails":"Mr A Investor <Family A/C>","holderType":"Individual\"""")
                    .replace("[\"BROKER\"]", "[\"INVESTOR\"]"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.engagementStyle").value("Prefers quarterly calls"))
                    .andExpect(jsonPath("$.values.preferredOfferings", hasSize(2)))
                    .andExpect(jsonPath("$.values.loanSectors", hasSize(2)))
                    .andExpect(jsonPath("$.values.wealthIndustry", hasSize(3)))
                    .andExpect(jsonPath("$.values.holderId").value("X0012345678"))
                    .andExpect(jsonPath("$.values.registrationDetails").value("Mr A Investor <Family A/C>"));
        }
    }

    @Nested
    @DisplayName("Communication consent and Marketing blocks")
    class Consent {

        @ParameterizedTest(name = "channel {0} needs Preferred phone")
        @ValueSource(strings = {"SMS", "PHONE"})
        @DisplayName("Preferred phone is required when the preferred channel is SMS or Phone")
        void preferredPhoneForSmsOrPhone(String channel) throws Exception {
            createWith(",\"preferredChannels\":[\"" + channel + "\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preferredPhone").value("Preferred phone is required."))
                    .andExpect(jsonPath("$.errors.preferredEmail").doesNotExist());
        }

        @Test
        @DisplayName("Preferred email is required only when the preferred channel is Email")
        void preferredEmailForEmail() throws Exception {
            createWith(",\"preferredChannels\":[\"EMAIL\"]").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preferredEmail").value("Preferred email is required."))
                    .andExpect(jsonPath("$.errors.preferredPhone").doesNotExist());
        }

        @ParameterizedTest(name = "marketing consent {0} needs channels")
        @ValueSource(strings = {"OPTED_IN", "OPTED_OUT"})
        @DisplayName("Marketing channels are required whether the Person opted in or opted out")
        void marketingChannelsRegardlessOfConsent(String consent) throws Exception {
            createWith(",\"marketingConsent\":\"" + consent + "\"").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.marketingChannels").value("Marketing channels is required."));
        }

        @ParameterizedTest(name = "marketing channel {0} needs Preferred marketing phone")
        @ValueSource(strings = {"SMS", "PHONE"})
        @DisplayName("Preferred marketing phone is required for SMS or phone marketing")
        void preferredMarketingPhone(String channel) throws Exception {
            String base = ",\"marketingConsent\":\"OPTED_IN\",\"marketingChannels\":[\"" + channel + "\"]";
            createWith(base).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preferredMarketingPhone").exists())
                    .andExpect(jsonPath("$.errors.preferredMarketingEmail").doesNotExist());
            createWith(base + ",\"preferredMarketingPhone\":\"0412 345 678\"").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Preferred marketing email is required for email marketing and must be an email")
        void preferredMarketingEmail() throws Exception {
            String base = ",\"marketingConsent\":\"OPTED_IN\",\"marketingChannels\":[\"EMAIL\"]";
            createWith(base).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.preferredMarketingEmail").exists());
            createWith(base + ",\"preferredMarketingEmail\":\"not-an-email\"").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.preferredMarketingEmail", containsString("valid format")));
            createWith(base + ",\"preferredMarketingEmail\":\"news@example.test\",\"consentGivenDate\":\"2026-11-01\"")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.consentGivenDate").value("2026-11-01"));
        }

        @Test
        @DisplayName("Marketing channels are not asked for while Marketing consent is empty")
        void noChannelsWithoutConsent() throws Exception {
            createWith(",\"consentWithdrawnDate\":\"2026-10-01\"").andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("Marketing / LinkedIn, Mailchimp and Flag blocks")
    class MarketingFlags {

        @Test
        @DisplayName("Mailchimp audience checkboxes keep a tick; an untick is stored as no value")
        void mailchimpCheckboxes() throws Exception {
            String id = create(",\"mailchimpAdded\":true,\"mailchimpSubscribed\":true,\"mailchimpCleaned\":false");
            getJson("/api/persons/" + id)
                    .andExpect(jsonPath("$.values.mailchimpAdded").value(true))
                    .andExpect(jsonPath("$.values.mailchimpSubscribed").value(true))
                    .andExpect(jsonPath("$.values.mailchimpCleaned").doesNotExist());
            createWith(",\"mailchimpUnsubscribed\":\"yes\"").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.mailchimpUnsubscribed").exists());
        }

        @ParameterizedTest(name = "LinkedIn status {0}")
        @ValueSource(strings = {"ACCEPTED_CONNECTION_REQUEST", "ACCEPTED_CONNECTION_REQUEST_BUT_NO_RESPONSE",
                "ACCEPTED_CONNECTION_REQUEST_BUT_NOT_INTERESTED", "ACCEPTED_CONNECTION_REQUEST_POSITION_RESPONSE",
                "CONNECTION_REQUEST_SENT", "DID_NOT_ACCEPT_CONNECTION_REQUEST"})
        @DisplayName("every LinkedIn Connection Status of the field map is accepted")
        void linkedinStatuses(String status) throws Exception {
            createWith(",\"linkedinStatus\":\"" + status + "\"").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("LinkedIn Connect Age is set by the CRM, never typed")
        void linkedinAgeIsSystem() throws Exception {
            createWith(",\"linkedinAgeWeeks\":4").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.linkedinAgeWeeks").exists());
        }

        @ParameterizedTest(name = "Engagement Temperature {0}")
        @ValueSource(strings = {"HOT", "WARM", "COLD"})
        @DisplayName("Engagement Temperature takes Hot, Warm or Cold")
        void engagementTemperature(String value) throws Exception {
            createWith(",\"engagementTemperature\":\"" + value + "\"").andExpect(status().isCreated());
        }

        @Test
        @DisplayName("Data quality takes one value from the client's six")
        void dataQuality() throws Exception {
            createWith(",\"dataQuality\":\"RE_ENRICHMENT\"").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.values.dataQuality").value("RE_ENRICHMENT"));
            createWith(",\"dataQuality\":[\"COMPLETE\",\"DATA_INGESTION\"]").andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Linked entities: Entity Role")
    class EntityRole {

        @Test
        @DisplayName("a user can add a new Entity Role value")
        void newRoleCanBeAdded() throws Exception {
            String label = "Board observer " + unique();
            postJson("/api/metadata/picklists/ENTITY_LINK_ROLE/values", "{\"label\":\"" + label + "\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.label").value(label));
            getJson("/api/metadata")
                    .andExpect(jsonPath("$.picklists.ENTITY_LINK_ROLE.values[*].label", hasItem(label)));
        }

        /** GAP-01 (fixed). Field notes: "User can add new picklist value as long as it does not exist". */
        @ParameterizedTest(name = "\"{0}\" is refused")
        @ValueSource(strings = {"Decision maker", "decision MAKER", "  Decision   maker  "})
        @DisplayName("adding an Entity Role that already exists is refused (any letter case or spacing)")
        void existingRoleIsRefused(String label) throws Exception {
            postJson("/api/metadata/picklists/ENTITY_LINK_ROLE/values", "{\"label\":\"" + label + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.label").value("\"Decision maker\" is already in this list."));
            getJson("/api/metadata")
                    .andExpect(jsonPath("$.picklists.ENTITY_LINK_ROLE.values[?(@.code == 'DECISION_MAKER_2')]", hasSize(0)));
        }

        @Test
        @DisplayName("a retired Entity Role is not added again; the user is told to make it active")
        void retiredRoleIsNotAddedAgain() throws Exception {
            String label = "Temp role " + unique();
            String code = read(postJson("/api/metadata/picklists/ENTITY_LINK_ROLE/values", "{\"label\":\"" + label + "\"}")
                    .andExpect(status().isCreated()), "$.code");
            putJson("/api/metadata/picklists/ENTITY_LINK_ROLE/values/" + code, "{\"active\":false}")
                    .andExpect(status().isNoContent());
            postJson("/api/metadata/picklists/ENTITY_LINK_ROLE/values", "{\"label\":\"" + label.toUpperCase() + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.label", containsString("Make it active again")));
        }
    }
}
