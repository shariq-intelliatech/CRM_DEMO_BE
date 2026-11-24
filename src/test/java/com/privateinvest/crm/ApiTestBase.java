package com.privateinvest.crm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base for API tests: a real Spring context on the CRM-Demo-test database. The database is wiped
 * and migrated once per test run; each test creates its own uniquely named records.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class ApiTestBase {

    private static boolean databaseReset;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SpringLiquibase liquibase;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMvc() throws Exception {
        synchronized (ApiTestBase.class) {
            if (!databaseReset) {
                try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
                }
                liquibase.afterPropertiesSet();
                databaseReset = true;
            }
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    protected ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    protected ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Same requests as another dev user (sent as the X-Dev-User header), to test permissions. */
    protected ResultActions getAs(String user, String url) throws Exception {
        return mvc.perform(get(url).header("X-Dev-User", user));
    }

    protected ResultActions postAs(String user, String url, String body) throws Exception {
        return mvc.perform(post(url).header("X-Dev-User", user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions putAs(String user, String url, String body) throws Exception {
        return mvc.perform(put(url).header("X-Dev-User", user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * A section save on a Person or Entity, sent with the record's current version in If-Match
     * (as the record page does). The record is read from the first four path parts of the url.
     */
    protected ResultActions putCurrent(String url, String body) throws Exception {
        return putCurrentAs("u1", url, body);
    }

    protected ResultActions putCurrentAs(String user, String url, String body) throws Exception {
        return mvc.perform(put(url).header("X-Dev-User", user).header("If-Match", versionOf(recordUrl(url)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A save sent with a given If-Match value, e.g. an old version from a stale screen. */
    protected ResultActions putVersion(String url, String ifMatch, String body) throws Exception {
        return mvc.perform(put(url).header("If-Match", ifMatch).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected String versionOf(String recordUrl) throws Exception {
        return read(getJson(recordUrl), "$.version");
    }

    private static String recordUrl(String url) {
        String[] parts = url.split("/");
        return String.join("/", parts[0], parts[1], parts[2], parts[3]);
    }

    protected static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    /**
     * A random name fragment so tests never collide with each other's records. Letters only: quick search also
     * matches the digits typed against phone numbers, so a fragment with digits could find an unrelated record.
     */
    protected static String unique() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    /** A random but valid ACN (check digit computed). */
    protected static String validAcn() {
        int[] weights = {8, 7, 6, 5, 4, 3, 2, 1};
        StringBuilder d = new StringBuilder();
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            int digit = ThreadLocalRandom.current().nextInt(10);
            d.append(digit);
            sum += weights[i] * digit;
        }
        return d.append((10 - sum % 10) % 10).toString();
    }

    /**
     * The values every new Person needs under the client field map besides its name and type (address, one
     * phone, one email), as JSON object members without braces. Email and mobile are unique, so tests don't trip
     * each other's duplicate check.
     */
    protected static String requiredPerson() {
        String u = unique();
        return requiredPerson("p." + u + "@example.test", "04" + randomDigits(8));
    }

    protected static String requiredPerson(String email, String mobile) {
        return """
                "street":"1 Test Street","addressLine2":"Level 2","suburb":"Sydney","state":"NSW","postcode":"2000","country":"Australia",
                "phones":[{"label":"MOBILE","value":"%s","primary":true}],
                "emails":[{"label":"BUSINESS","value":"%s","primary":true}]""".formatted(mobile, email);
    }

    /** A complete new-Person body: {"values": {...}} with a name, a type and every required field. */
    protected static String personBody(String first, String last, String extraMembers) {
        return """
                {"duplicateReason":null,"values":{"firstName":"%s","lastName":"%s","personTypes":["BROKER"],%s%s}}"""
                .formatted(first, last, requiredPerson(), extraMembers == null ? "" : extraMembers);
    }

    /** A complete new-Entity body: {"values": {...}} with name, type, ACN and ABN. */
    protected static String entityBody(String name, String acn, String type, String extraMembers) {
        return """
                {"values":{"name":"%s","entityType":"%s"%s%s%s}}""".formatted(name, type,
                acn == null ? "" : ",\"acn\":\"" + acn + "\"", acn == null ? "" : ",\"abn\":\"" + abnFor(acn) + "\"",
                extraMembers == null ? "" : extraMembers);
    }

    protected static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    /** A valid ABN wrapping the given ACN, as the ATO does for companies. */
    protected static String abnFor(String acn) {
        for (int p = 10; p <= 99; p++) {
            String candidate = p + acn;
            if (com.privateinvest.crm.common.util.Formats.isValidAbn(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No ABN prefix found");
    }
}
