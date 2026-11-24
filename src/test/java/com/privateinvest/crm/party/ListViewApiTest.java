package com.privateinvest.crm.party;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import com.privateinvest.crm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Salesforce-style list views: numbered filters with filter logic, more operators, relative dates, saved views. */
class ListViewApiTest extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Australia/Sydney"));

    private String person(String first, String last, String type, String dob, String owner) throws Exception {
        return read(postAs(owner, "/api/persons", """
                {"duplicateReason":"Test data","values":{"firstName":"%s","lastName":"%s","personTypes":["%s"],%s%s}}"""
                .formatted(first, last, type, requiredPerson(), dob == null ? "" : ",\"dateOfBirth\":\"" + dob + "\""))
                .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions search(String body) throws Exception {
        return postJson("/api/persons/search", body);
    }

    /** Conditions 1..n where condition 1 always pins the search to this test's last name. */
    private ResultActions filter(String last, String logic, String... conditions) throws Exception {
        String all = "{\"field\":\"lastName\",\"operator\":\"EQUALS\",\"value\":\"" + last + "\"}"
                + (conditions.length == 0 ? "" : "," + String.join(",", conditions));
        return search("{\"conditions\":[" + all + "]" + (logic == null ? "" : ",\"logic\":\"" + logic + "\"") + "}");
    }

    @Test
    void filterLogicCombinesNumberedConditions() throws Exception {
        String u = "Lg" + unique();
        person("Ann", u, "BROKER", "1970-01-01", "u1");
        person("Bea", u, "INVESTOR", "1990-01-01", "u1");
        person("Cal", u, "SERVICE_PROVIDER", "2000-01-01", "u1");
        String broker = "{\"field\":\"personTypes\",\"operator\":\"CONTAINS_ANY\",\"value\":[\"BROKER\"]}";
        String investor = "{\"field\":\"personTypes\",\"operator\":\"CONTAINS_ANY\",\"value\":[\"INVESTOR\"]}";
        String young = "{\"field\":\"dateOfBirth\",\"operator\":\"AFTER\",\"value\":\"1995-01-01\"}";

        filter(u, "1 AND (2 OR 3)", broker, investor).andExpect(jsonPath("$.totalElements").value(2));
        filter(u, "1 AND (2 OR 3 OR 4)", broker, investor, young).andExpect(jsonPath("$.totalElements").value(3));
        filter(u, "1 AND NOT (2 OR 3)", broker, investor).andExpect(jsonPath("$.items[0].title").value("Cal " + u));
        filter(u, null, broker).andExpect(jsonPath("$.totalElements").value(1)); // blank logic = AND

        filter(u, "1 AND 2 OR 3", broker, investor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.logic", containsString("parentheses")));
        filter(u, "1 AND 4", broker, investor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.logic", containsString("no filter 4")));
        filter(u, "1 AND 2", broker, investor).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.logic", containsString("Filter 3 isn't used")));
        filter(u, "1 AND (2 OR 3", broker, investor).andExpect(status().isBadRequest());
        filter(u, "1; DROP TABLE person", broker).andExpect(status().isBadRequest());
    }

    @Test
    void salesforceOperatorsWork() throws Exception {
        String u = "Op" + unique();
        person("Alpha", u, "BROKER", "1980-06-15", "u1");
        person("Beta", u, "INVESTOR", "1985-06-15", "u1");
        person("Gamma", u, "INVESTOR", null, "u1");

        filter(u, null, "{\"field\":\"firstName\",\"operator\":\"NOT_CONTAINS\",\"value\":\"lph\"}")
                .andExpect(jsonPath("$.totalElements").value(2));
        filter(u, null, "{\"field\":\"firstName\",\"operator\":\"EQUALS\",\"value\":[\"alpha\",\"GAMMA\"]}")
                .andExpect(jsonPath("$.totalElements").value(2));                            // equals A, B
        filter(u, null, "{\"field\":\"firstName\",\"operator\":\"CONTAINS\",\"value\":[\"alp\",\"amm\"]}")
                .andExpect(jsonPath("$.totalElements").value(2));
        filter(u, null, "{\"field\":\"personTypes\",\"operator\":\"EXCLUDES\",\"value\":[\"INVESTOR\"]}")
                .andExpect(jsonPath("$.items[0].title").value("Alpha " + u));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"GTE\",\"value\":\"1985-06-15\"}")
                .andExpect(jsonPath("$.totalElements").value(1));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"LTE\",\"value\":\"1985-06-15\"}")
                .andExpect(jsonPath("$.totalElements").value(2));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"NOT_EQUALS\",\"value\":\"1980-06-15\"}")
                .andExpect(jsonPath("$.totalElements").value(2));                            // empty counts as "not equal"
        filter(u, null, "{\"field\":\"ownerId\",\"operator\":\"NOT_IN\",\"value\":[\"u2\"]}")
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void relativeDatesFollowTodayInTheCrmTimeZone() throws Exception {
        String u = "Rd" + unique();
        person("Yesterday", u, "BROKER", TODAY.minusDays(1).toString(), "u1");
        person("LastYear", u, "BROKER", TODAY.minusYears(1).withDayOfYear(1).toString(), "u1");
        person("Tomorrow", u, "BROKER", TODAY.plusDays(1).toString(), "u1");

        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"EQUALS\",\"value\":\"YESTERDAY\"}")
                .andExpect(jsonPath("$.items[0].title").value("Yesterday " + u));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"EQUALS\",\"value\":\"LAST_N_DAYS:3\"}")
                .andExpect(jsonPath("$.totalElements").value(1));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"EQUALS\",\"value\":\"NEXT_N_DAYS:3\"}")
                .andExpect(jsonPath("$.items[0].title").value("Tomorrow " + u));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"EQUALS\",\"value\":\"LAST_YEAR\"}")
                .andExpect(jsonPath("$.items[0].title").value("LastYear " + u));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"BEFORE\",\"value\":\"TODAY\"}")
                .andExpect(jsonPath("$.totalElements").value(2));
        filter(u, null, "{\"field\":\"dateOfBirth\",\"operator\":\"EQUALS\",\"value\":\"SOMEDAY\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void showMeMineLimitsToTheCurrentUsersPersons() throws Exception {
        String u = "Mn" + unique();
        person("Mine", u, "BROKER", null, "u2");
        person("Theirs", u, "BROKER", null, "u3");
        postAs("u2", "/api/persons/search", "{\"ownerScope\":\"MINE\",\"q\":\"" + u + "\"}")
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].title").value("Mine " + u));
    }

    @Test
    void listViewsAreSavedPrivateOrSharedAndPinned() throws Exception {
        String u = unique();
        String body = """
                {"name":"Investors %s","shared":%s,"ownerScope":"ALL",
                 "conditions":[{"field":"personTypes","operator":"CONTAINS_ANY","value":["INVESTOR"]},
                               {"field":"dateOfBirth","operator":"EQUALS","value":"THIS_YEAR"}],
                 "logic":"1 OR 2","columns":["firstName","lastName","emails"],"sort":[{"field":"lastName","dir":"DESC"}]}""";

        // A standard user keeps private views, but can't share.
        String mine = read(postAs("u2", "/api/list-views", body.formatted(u, false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.logic").value("1 OR 2"))
                .andExpect(jsonPath("$.conditions[1].value").value("THIS_YEAR"))
                .andExpect(jsonPath("$.canEdit").value(true)), "$.id");
        postAs("u2", "/api/list-views", body.formatted(u + "s", true)).andExpect(status().isForbidden());
        getAs("u3", "/api/list-views").andExpect(jsonPath("$[*].id").value(not(hasItem(mine))));
        getAs("u2", "/api/list-views").andExpect(jsonPath("$[*].id").value(hasItem(mine)));
        postAs("u2", "/api/list-views", body.formatted(u, false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        // An admin shares one; everyone sees it, only the owner (or a sharer) changes it.
        String shared = read(postJson("/api/list-views", body.formatted(u + "s", true)).andExpect(status().isCreated()), "$.id");
        getAs("u3", "/api/list-views").andExpect(jsonPath("$[?(@.id == '" + shared + "')].canEdit").value(hasItem(false)));
        mvc.perform(put("/api/list-views/" + shared).header("X-Dev-User", "u3").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(u + "x", false))).andExpect(status().isForbidden());

        // Anyone can pin a view they can see.
        mvc.perform(put("/api/list-views/" + shared + "/pin").header("X-Dev-User", "u3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
        getJson("/api/list-views").andExpect(jsonPath("$[?(@.id == '" + shared + "')].pinned").value(hasItem(false)));

        // A view must be valid against today's fields; edits need the loaded version.
        postJson("/api/list-views", """
                {"name":"Bad %s","conditions":[{"field":"nope","operator":"EQUALS","value":"x"}]}""".formatted(u))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/list-views/" + mine).header("X-Dev-User", "u2").header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(u + " renamed", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Investors " + u + " renamed"));
        mvc.perform(put("/api/list-views/" + mine).header("X-Dev-User", "u2").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(u, false))).andExpect(status().isConflict());

        mvc.perform(delete("/api/list-views/" + mine).header("X-Dev-User", "u3")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/list-views/" + mine).header("X-Dev-User", "u2")).andExpect(status().isNoContent());
        getAs("u2", "/api/list-views/" + mine).andExpect(status().isNotFound());
    }
}
