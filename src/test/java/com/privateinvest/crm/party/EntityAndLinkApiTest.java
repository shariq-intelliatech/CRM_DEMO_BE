package com.privateinvest.crm.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Entities on the configurable-record engine (brief §3.3): hard gate G1 on ACN and ABN, G2 formats, the similar-name
 * warning, the Entity type review queue, conditional fields; Entity links (§3.4, §3.5.2) and what a Person reads
 * through them.
 */
class EntityAndLinkApiTest extends ApiTestBase {

    @Autowired
    private JdbcClient jdbc;

    private String createEntity(String name, String acn, String type) throws Exception {
        return read(postJson("/api/entities", entityBody(name, acn, type, "")).andExpect(status().isCreated()), "$.id");
    }

    private String createPerson(String last, String email) throws Exception {
        return read(postJson("/api/persons", """
                {"values":{"firstName":"Linky","lastName":"%s","personTypes":["BROKER"],%s}}"""
                .formatted(last, requiredPerson(email, "04" + randomDigits(8)))).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions patchEntity(String id, String values) throws Exception {
        return mvc.perform(patch("/api/entities/" + id).header("If-Match", versionOf("/api/entities/" + id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + values + "}"));
    }

    private ResultActions search(String body) throws Exception {
        return postJson("/api/entities/search", body);
    }

    /* ---------- Entity ---------- */

    @Test
    void createsEntityFromFieldValuesAndStoresDigitsOnly() throws Exception {
        String acn = validAcn();
        String spaced = acn.substring(0, 3) + " " + acn.substring(3, 6) + " " + acn.substring(6);
        String u = unique();
        postJson("/api/entities", """
                {"values":{"name":"Spaced %s Pty Ltd","acn":"%s","abn":"%s","entityType":"BROKER_FIRM",
                 "emails":[{"label":"BUSINESS","value":"a.%s@b.example"}],
                 "phones":[{"label":"OFFICE","value":"02 9000 1111"},{"label":"BUSINESS_2","value":"02 9000 2222"}],
                 "firstSourceChannel":"REFERRAL"}}""".formatted(u, spaced, abnFor(acn), u))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("entity"))
                .andExpect(jsonPath("$.title").value("Spaced " + u + " Pty Ltd"))
                .andExpect(jsonPath("$.values.acn").value(acn))
                .andExpect(jsonPath("$.values.abn").value(abnFor(acn)))
                .andExpect(jsonPath("$.values.ownerId").value("u1"))
                .andExpect(jsonPath("$.values.phones[0].primary").value(true))
                .andExpect(jsonPath("$.values.firstSourceChannel").value("REFERRAL"))
                .andExpect(jsonPath("$.persons", hasSize(0)));
        getJson("/api/metadata/objects/entity/fields")
                .andExpect(jsonPath("$[?(@.apiName == 'acn')].validation.format").value(hasItem("ACN")))
                .andExpect(jsonPath("$[?(@.apiName == 'entityType')].mandatory").value(hasItem(true)));
    }

    @Test
    void acnAndAbnFormatErrorsSayWhatIsWrong() throws Exception {
        // Production checks the ASIC and ATO check digits (hard gate G2): the right length isn't enough.
        postJson("/api/entities", """
                {"values":{"name":"Bad Check Digits %s","acn":"123456789","abn":"12345678901","entityType":"BROKER_FIRM"}}""".formatted(unique()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.acn", org.hamcrest.Matchers.containsString("check digit")))
                .andExpect(jsonPath("$.errors.abn", org.hamcrest.Matchers.containsString("ABN check")));
        String acn = validAcn();
        postJson("/api/entities", """
                {"values":{"name":"Good Check Digits %s","acn":"%s","abn":"%s","entityType":"BROKER_FIRM"}}"""
                .formatted(unique(), acn, abnFor(acn)))
                .andExpect(status().isCreated());
        postJson("/api/entities", """
                {"values":{"name":"Short Numbers %s","acn":"1234","abn":"99","entityType":"BROKER_FIRM",
                 "emails":[{"label":"BUSINESS","value":"nope"}]}}""".formatted(unique()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.acn").value("An ACN has 9 digits. This one has 4."))
                .andExpect(jsonPath("$.errors.abn").value("An ABN has 11 digits. This one has 2."))
                .andExpect(jsonPath("$.errors.emails").exists());
    }

    @Test
    void entityTypeIsRequiredAndOtherNeedsANote() throws Exception {
        postJson("/api/entities", """
                {"values":{"name":"No Type %s"}}""".formatted(unique()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entityType").exists());
        postJson("/api/entities", """
                {"values":{"name":"Odd Type %s","entityType":"SPACESHIP"}}""".formatted(unique()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entityType").exists());
        String u = unique();
        postJson("/api/entities", entityBody("Other Kind " + u, null, "OTHER", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.typeNote").value("Type note is required."));
        postJson("/api/entities", entityBody("Other Kind " + u, null, "OTHER", ",\"typeNote\":\"Runs a co-working space\""))
                .andExpect(status().isCreated());
        // The Aggregator Office type exists alongside Aggregator Group.
        postJson("/api/entities", entityBody("Panel Office " + unique(), validAcn(), "AGGREGATOR_OFFICE", ""))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateAcnOrAbnIsBlockedOnCreateEditAndRestore() throws Exception {
        String acn = validAcn();
        String first = createEntity("Original " + unique() + " Pty Ltd", acn, "BROKER_FIRM");

        // A reason never passes the hard gate.
        postJson("/api/entities", """
                {"values":{"name":"Copycat %s","acn":"%s","entityType":"BROKER_FIRM"},"duplicateReason":"try to override"}"""
                .formatted(unique(), acn))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-block"))
                .andExpect(jsonPath("$.field").value("ACN"))
                .andExpect(jsonPath("$.existingId").value(first))
                .andExpect(jsonPath("$.detail", containsString("Two Entities can't share an ACN")));
        postJson("/api/entities", """
                {"values":{"name":"Copycat ABN %s","abn":"%s","entityType":"BROKER_FIRM"}}""".formatted(unique(), abnFor(acn)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.field").value("ABN"));

        String otherAcn = validAcn();
        String second = createEntity("Second " + unique() + " Pty Ltd", otherAcn, "DEVELOPER");
        patchEntity(second, "{\"acn\":\"" + acn + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(first));
        // Saving other fields with its own numbers is fine.
        patchEntity(second, "{\"website\":\"second.example\"}").andExpect(status().isOk());

        // An archived Entity frees its numbers; restoring it while another holds them is blocked.
        String archivedVersion = read(mvc.perform(delete("/api/entities/" + first).header("If-Match", versionOf("/api/entities/" + first))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Merged\"}")).andExpect(status().isOk()), "$.version");
        String third = createEntity("Third " + unique() + " Pty Ltd", acn, "DEVELOPER");
        mvc.perform(post("/api/entities/" + first + "/restore").header("If-Match", archivedVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(third));
        // The database guards the gate too.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE entity SET custom_fields = custom_fields || jsonb_build_object('acn', CAST(:a AS text)) WHERE id = CAST(:id AS uuid)")
                        .param("a", otherAcn).param("id", third).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void similarNameWarnsAndCanBeOverriddenOntoTheDuplicateQueue() throws Exception {
        String base = "Harbourview Lending " + unique();
        createEntity(base + " Pty Ltd", validAcn(), "BROKER_FIRM");
        postJson("/api/entities", entityBody(base, null, "BROKER_FIRM", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-warning"))
                .andExpect(jsonPath("$.matches[0].reasons[0]").value("Similar name"));
        String id = read(postJson("/api/entities", entityBody(base, null, "BROKER_FIRM", "")
                        .replace("{\"values\"", "{\"duplicateReason\":\"Separate trading entity\",\"values\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flags.possibleDuplicateReason").value("Separate trading entity")), "$.id");
        search("{\"flag\":\"POSSIBLE_DUPLICATE\",\"size\":100}").andExpect(jsonPath("$.items[*].id", hasItem(id)));
    }

    @Test
    void legalSuffixesDoNotMakeNamesLookSimilar() throws Exception {
        String u = unique();
        createEntity("Alphaz" + u + " Pty Ltd", validAcn(), "BROKER_FIRM");
        postJson("/api/entities", entityBody("Betaq" + unique() + " Pty Ltd", null, "BROKER_FIRM", ""))
                .andExpect(status().isCreated());
        postJson("/api/entities", entityBody("Alphaz" + u + " Pty. Limited", null, "BROKER_FIRM", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate-warning"));
    }

    @Test
    void searchByNameAcnAbnAndTheReviewQueue() throws Exception {
        String acn = validAcn();
        String u = unique();
        String id = createEntity("Searchable " + u + " Pty Ltd", acn, "NOT_YET_KNOWN");
        search("{\"q\":\"" + acn.substring(2, 8) + "\"}").andExpect(jsonPath("$.items[*].id", hasItem(id)));
        search("{\"q\":\"" + abnFor(acn) + "\"}").andExpect(jsonPath("$.items[*].id", hasItem(id)));
        search("{\"q\":\"searchable " + u + "\"}").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.columns", hasItem("entityType")));
        String queue = "{\"conditions\":[{\"field\":\"entityType\",\"operator\":\"IN\",\"value\":[\"NOT_YET_KNOWN\",\"OTHER\"]}],\"size\":100}";
        search(queue).andExpect(jsonPath("$.items[*].id", hasItem(id)));
        search("{\"ownerScope\":\"MINE\",\"q\":\"" + u + "\"}").andExpect(jsonPath("$.totalElements").value(1));

        patchEntity(id, "{\"entityType\":\"DEVELOPER\"}").andExpect(status().isOk());
        search(queue).andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
        getJson("/api/metadata/objects/entity/rules")
                .andExpect(jsonPath("$.rules.reviewQueue.values", hasItem("OTHER")))
                .andExpect(jsonPath("$.rules.uniqueFields", hasItem("acn")))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        // The picker can be narrowed to one type, e.g. aggregators for a broker's panel.
        String a = unique();
        String agg = createEntity("Aggrega " + a + " Pty Ltd", validAcn(), "AGGREGATOR_GROUP");
        createEntity("Broking " + a.toUpperCase().substring(0, 1) + unique() + " Pty Ltd", validAcn(), "BROKER_FIRM");
        getJson("/api/entities/lookup?q=" + a + "&field=entityType&values=AGGREGATOR_GROUP,AGGREGATOR_OFFICE")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(agg))
                .andExpect(jsonPath("$.items[0].subtitle", containsString("Aggregator Group")));
    }

    @Test
    void defaultSearchOrderShowsNewestCreatedEntityFirst() throws Exception {
        String u = unique();
        createEntity("Alpha First " + u + " Pty Ltd", null, "BROKER_FIRM");
        String newestId = createEntity("Zebra Newest " + u + " Pty Ltd", null, "BROKER_FIRM");
        search("{}").andExpect(jsonPath("$.items[0].id").value(newestId));
    }

    @Test
    void standardUsersWorkWithEntitiesButCannotArchive() throws Exception {
        String id = createEntity("Perm " + unique() + " Pty Ltd", validAcn(), "BUILDER");
        getAs("u2", "/api/entities/" + id).andExpect(status().isOk());
        mvc.perform(delete("/api/entities/" + id).header("X-Dev-User", "u2").header("If-Match", versionOf("/api/entities/" + id)))
                .andExpect(status().isForbidden());
        postAs("u2", "/api/entities", entityBody("Made By Two " + unique(), validAcn(), "BUILDER", ""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.values.ownerId").value("u2"));
    }

    @Test
    void staleEntityScreenCannotOverwriteANewerSave() throws Exception {
        String id = createEntity("Version Co " + unique() + " Pty Ltd", validAcn(), "DEVELOPER");
        String url = "/api/entities/" + id;
        String loaded = versionOf(url);
        mvc.perform(patch(url).header("If-Match", loaded).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"website\":\"b.example\"}}")).andExpect(status().isOk());
        mvc.perform(patch(url).header("If-Match", loaded).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"website\":\"a.example\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale"));
        putVersion(url + "/custom-fields", loaded, "{\"values\":{}}").andExpect(status().isConflict());
        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content("{\"values\":{}}")).andExpect(status().is(428));
        getJson(url).andExpect(jsonPath("$.values.website").value("b.example"));
    }

    /* ---------- Entity links ---------- */

    @Test
    void archivedRecordsDropOutOfLinks() throws Exception {
        String u = unique();
        String person = createPerson("Archive" + u, "archive." + u + "@example.test");
        String entity = createEntity("Archive Co " + u + " Pty Ltd", validAcn(), "BROKER_FIRM");
        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"DECISION_MAKER"}""".formatted(person, entity)).andExpect(status().isCreated());
        getJson("/api/entities/" + entity).andExpect(jsonPath("$.persons", hasSize(1)))
                .andExpect(jsonPath("$.persons[0].primaryEmail").value("archive." + u + "@example.test"));

        jdbc.sql("UPDATE person SET archived_at = now() WHERE id = CAST(:id AS uuid)").param("id", person).update();
        getJson("/api/entities/" + entity).andExpect(jsonPath("$.persons", hasSize(0)));
        getJson("/api/persons/" + person).andExpect(status().isNotFound());
    }

    @Test
    void firstLinkIsPrimaryAndPersonInheritsEntityType() throws Exception {
        String u = unique();
        String person = createPerson("First" + u, "first." + u + "@example.test");
        String developer = createEntity("Dev Co " + u + " Pty Ltd", validAcn(), "DEVELOPER");
        String builder = createEntity("Build Co " + unique() + " Pty Ltd", validAcn(), "BUILDER");

        String firstLink = read(postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"DECISION_MAKER","jobTitle":"MD"}""".formatted(person, developer))
                .andExpect(status().isCreated()), "$.id");
        String second = read(postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"TRUSTEE"}""".formatted(person, builder))
                .andExpect(status().isCreated()), "$.id");

        getJson("/api/persons/" + person)
                .andExpect(jsonPath("$.links", hasSize(2)))
                .andExpect(jsonPath("$.inheritedEntityType").value("DEVELOPER"))
                .andExpect(jsonPath("$.context.linkedEntityType", hasItem("BUILDER")));

        putJson("/api/entity-links/" + second + "/primary", "").andExpect(status().isNoContent());
        ResultActions after = getJson("/api/persons/" + person)
                .andExpect(jsonPath("$.inheritedEntityType").value("BUILDER"));
        assertThat(read(after, "$.links[?(@.primary == true)].entityId")).contains(builder).doesNotContain(developer);

        getJson("/api/entities/" + developer)
                .andExpect(jsonPath("$.persons[0].personId").value(person))
                .andExpect(jsonPath("$.persons[0].personName").value("Linky First" + u))
                .andExpect(jsonPath("$.persons[0].role").value("DECISION_MAKER"));
        getJson("/api/persons/" + person).andExpect(jsonPath("$.links[?(@.primary == true)].entityName",
                hasItem(containsString("Build Co"))));

        // Unlinking the primary Entity makes the remaining link primary.
        mvc.perform(delete("/api/entity-links/" + second)).andExpect(status().isNoContent());
        getJson("/api/persons/" + person).andExpect(jsonPath("$.links", hasSize(1)))
                .andExpect(jsonPath("$.links[0].id").value(firstLink))
                .andExpect(jsonPath("$.links[0].primary").value(true))
                .andExpect(jsonPath("$.inheritedEntityType").value("DEVELOPER"));
    }

    @Test
    void brokerProfileShowsWhenABrokerLinksToAnAggregator() throws Exception {
        String u = unique();
        String person = createPerson("Broker" + u, "broker." + u + "@example.test");
        String agg = createEntity("Panel Group " + u + " Pty Ltd", validAcn(), "AGGREGATOR_GROUP");
        getJson("/api/metadata/objects/person/fields")
                .andExpect(jsonPath("$[?(@.apiName == 'acr')].displayRules.visibleWhen[1].field").value(hasItem("$linkedEntityType")));
        getJson("/api/persons/" + person).andExpect(jsonPath("$.context.linkedEntityType", hasSize(0)));
        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"RELATIONSHIP_MANAGER"}""".formatted(person, agg)).andExpect(status().isCreated());
        getJson("/api/persons/" + person).andExpect(jsonPath("$.context.linkedEntityType", hasItem("AGGREGATOR_GROUP")));
        mvc.perform(patch("/api/persons/" + person).header("If-Match", versionOf("/api/persons/" + person))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"acr\":\"ACR-" + u + "\",\"industryBody\":\"MFAA\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values.industryBody").value("MFAA"));
    }

    @Test
    void newEmailTypedAtLinkIsAddedToPersonsEmails() throws Exception {
        String u = unique();
        String person = createPerson("Mail" + u, "home." + u + "@example.test");
        String entity = createEntity("Mail Co " + u + " Pty Ltd", validAcn(), "BROKER_FIRM");
        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"GATEKEEPER","email":"work.%s@example.test"}""".formatted(person, entity, u))
                .andExpect(status().isCreated());
        getJson("/api/persons/" + person)
                .andExpect(jsonPath("$.values.emails", hasSize(2)))
                .andExpect(jsonPath("$.values.emails[0].value").value("home." + u + "@example.test"))
                .andExpect(jsonPath("$.values.emails[0].primary").value(true))
                .andExpect(jsonPath("$.values.emails[1].value").value("work." + u + "@example.test"))
                .andExpect(jsonPath("$.values.emails[1].label").value("BUSINESS"))
                .andExpect(jsonPath("$.links[0].email").value("work." + u + "@example.test"));
        // The same email again isn't added twice.
        String other = createEntity("Second Inbox " + unique() + " Pty Ltd", validAcn(), "BUILDER");
        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"GATEKEEPER","email":"WORK.%s@example.test"}""".formatted(person, other, u))
                .andExpect(status().isCreated());
        getJson("/api/persons/" + person).andExpect(jsonPath("$.values.emails", hasSize(2)));
    }

    @Test
    void duplicateLinkRetiredRoleAndBadInputsAreRefused() throws Exception {
        String u = unique();
        String person = createPerson("Twice" + u, "twice." + u + "@example.test");
        String entity = createEntity("Twice Co " + u + " Pty Ltd", validAcn(), "BROKER_FIRM");
        String body = """
                {"personId":"%s","entityId":"%s","role":"FINANCE_LEAD"}""".formatted(person, entity);
        postJson("/api/entity-links", body).andExpect(status().isCreated());
        postJson("/api/entity-links", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.entityId").exists());
        // Director is retired by the field map's Entity Role list.
        postJson("/api/entity-links", """
                {"personId":"%s","entityId":"%s","role":"DIRECTOR"}""".formatted(person, createEntity("Role Co " + unique(), validAcn(), "BUILDER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").exists());
        postJson("/api/entity-links", """
                {"personId":"00000000-0000-4000-8000-000000000000","entityId":"%s","role":"INFLUENCER"}""".formatted(entity))
                .andExpect(status().isNotFound());
    }
}
