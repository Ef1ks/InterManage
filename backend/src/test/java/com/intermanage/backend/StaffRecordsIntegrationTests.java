package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Kadry: dostęp do danych wrażliwych, umowy, urlopy i orzeczenia przez API. Reguły prawa sprawdza StaffLawTests. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StaffRecordsIntegrationTests {

    private static final int YEAR = LocalDate.now().getYear();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String store;
    private String role;
    private String position;
    private String employee;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        store = createStore();
        role = createRole("{\"SALES\":\"VIEW\"}");
        String departments = send(owner, post(staff("/departments")), "{\"name\":\"Kasy\"}")
                .andReturn().getResponse().getContentAsString();
        String department = JsonPath.read(departments, "$[0].id");
        String positions = send(owner, post(staff("/positions")), "{\"departmentId\":\"" + department
                + "\",\"name\":\"Kasjer\",\"roleId\":\"" + role + "\",\"shifts\":[]}")
                .andReturn().getResponse().getContentAsString();
        position = JsonPath.read(positions, "$[0].positions[0].id");
        employee = invite();
    }

    @Test
    void employmentContractGivesLeaveAndCivilContractDoesNot() throws Exception {
        send(owner, post(records("/contracts")), contract("MANDATE", null, YEAR + "-01-01", null, 1, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaveYear.annualDays").value(0));

        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", (YEAR - 1) + "-01-01", null, 1, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contracts", hasSize(2)))
                .andExpect(jsonPath("$.leaveYear.annualDays").value(10));
    }

    @Test
    void contractMustBeComplete() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", null, "2026-01-01", null, 1, 1))
                .andExpect(status().isBadRequest());
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "FIXED", "2026-01-01", null, 1, 1))
                .andExpect(status().isBadRequest());
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2026-01-01", null, 3, 2))
                .andExpect(status().isBadRequest());
        send(owner, post(records("/contracts")), contract("B2B", null, "2026-02-01", "2026-01-01", 1, 1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameKindContractsCannotOverlap() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "FIXED", "2026-01-01", "2026-06-30", 1, 1))
                .andExpect(status().isOk());
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2026-06-01", null, 1, 1))
                .andExpect(status().isConflict());
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2026-07-01", null, 1, 1))
                .andExpect(status().isOk());
    }

    @Test
    void contractChangeAppliesFromItsDayAndCanBeUndone() throws Exception {
        String json = send(owner, post(records("/contracts")),
                contract("EMPLOYMENT", "INDEFINITE", (YEAR - 2) + "-01-01", null, 1, 1))
                .andReturn().getResponse().getContentAsString();
        String contract = JsonPath.read(json, "$.contracts[0].id");

        String change = "{\"validFrom\":\"" + YEAR + "-07-01\",\"fteNumerator\":1,\"fteDenominator\":2}";
        String changed = send(owner, post(records("/contracts/" + contract + "/changes")), change)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contracts[0].fteNumerator").value(1))
                .andExpect(jsonPath("$.contracts[0].fteDenominator").value(1))
                .andExpect(jsonPath("$.contracts[0].changes[0].fteDenominator").value(2))
                .andExpect(jsonPath("$.leaveYear.annualDays").value(15))
                .andReturn().getResponse().getContentAsString();
        send(owner, post(records("/contracts/" + contract + "/changes")), change).andExpect(status().isConflict());

        // Poprzedni rok liczy się dalej z pełnego etatu.
        mvc.perform(get(records("")).cookie(owner).param("year", String.valueOf(YEAR - 1)))
                .andExpect(jsonPath("$.leaveYear.annualDays").value(20));

        String changeId = JsonPath.read(changed, "$.contracts[0].changes[0].id");
        send(owner, delete(records("/contract-changes/" + changeId)), null)
                .andExpect(jsonPath("$.leaveYear.annualDays").value(20));
        send(owner, post(records("/contract-changes/" + changeId + "/restore")), null)
                .andExpect(jsonPath("$.leaveYear.annualDays").value(15));
    }

    @Test
    void contractChangeMustFallInsideContract() throws Exception {
        String json = send(owner, post(records("/contracts")),
                contract("EMPLOYMENT", "FIXED", "2026-01-01", "2026-06-30", 1, 1))
                .andReturn().getResponse().getContentAsString();
        String contract = JsonPath.read(json, "$.contracts[0].id");
        for (String day : new String[] {"2026-01-01", "2026-07-01"}) {
            send(owner, post(records("/contracts/" + contract + "/changes")),
                    "{\"validFrom\":\"" + day + "\",\"fteNumerator\":1,\"fteDenominator\":2}")
                    .andExpect(status().isBadRequest());
        }

        String b2b = send(owner, post(records("/contracts")), contract("B2B", null, "2026-01-01", null, 1, 1))
                .andReturn().getResponse().getContentAsString();
        String b2bId = JsonPath.read(b2b, "$.contracts[?(@.kind == 'B2B')].id[0]").toString();
        send(owner, post(records("/contracts/" + b2bId + "/changes")),
                "{\"validFrom\":\"2026-03-01\",\"fteNumerator\":1,\"fteDenominator\":2}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctingContractKeepsPreviousVersion() throws Exception {
        String json = send(owner, post(records("/contracts")),
                contract("EMPLOYMENT", "INDEFINITE", "2026-01-01", null, 1, 1))
                .andReturn().getResponse().getContentAsString();
        String contract = JsonPath.read(json, "$.contracts[0].id");
        send(owner, put(records("/contracts/" + contract)), contract("EMPLOYMENT", "INDEFINITE", "2026-02-01", null, 1, 2))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select start_date::text || ' ' || fte_denominator "
                + "from staff_contract_revisions where contract_id = ?::uuid", String.class, contract))
                .isEqualTo("2026-01-01 1");
    }

    @Test
    void leaveNeedsEmploymentContractAndOneYear() throws Exception {
        String leave = "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-03-02\",\"endDate\":\"2026-03-06\",\"days\":5}";
        send(owner, post(records("/leaves")), leave).andExpect(status().isConflict());

        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2025-01-01", null, 1, 1));
        send(owner, post(records("/leaves")), leave)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaveYear.usedDays").value(0))
                .andExpect(jsonPath("$.leaves", hasSize(1)));
        send(owner, post(records("/leaves")),
                "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-12-28\",\"endDate\":\"2027-01-02\",\"days\":4}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void leaveWithoutPlannedWorkHasNoDays() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2025-01-01", null, 1, 1));
        // Dni urlopu to dni ze zmianą w grafiku; bez grafiku urlop nic nie zużywa, a liczba z żądania się nie liczy.
        send(owner, post(records("/leaves")),
                "{\"kind\":\"ANNUAL\",\"startDate\":\"2026-11-07\",\"endDate\":\"2026-11-14\",\"days\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaves[0].days").value(0))
                .andExpect(jsonPath("$.leaveYear.usedDays").value(0));
    }

    @Test
    void removedEntryStaysAsMarkerAndComesBack() throws Exception {
        String json = send(owner, post(records("/contracts")), contract("B2B", null, "2026-01-01", null, 1, 1))
                .andReturn().getResponse().getContentAsString();
        String contract = JsonPath.read(json, "$.contracts[0].id");

        send(owner, delete(records("/contracts/" + contract)), null)
                .andExpect(jsonPath("$.contracts", hasSize(0)));
        assertThat(jdbc.queryForObject("select count(*) from staff_contracts where id = ?::uuid and deleted_at is not null",
                Long.class, contract)).isEqualTo(1);
        send(owner, post(records("/contracts/" + contract + "/restore")), null)
                .andExpect(jsonPath("$.contracts", hasSize(1)));
    }

    @Test
    void disabilityShowsRightsAndChecksFixedHours() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2020-01-01", null, 1, 1));
        send(owner, put(staff("/employees/" + employee)), "{\"displayName\":\"Jan Kowalski\",\"email\":\""
                + UUID.randomUUID() + "@sklep.pl\",\"positionId\":\"" + position
                + "\",\"shifts\":[{\"dayOfWeek\":\"MONDAY\",\"start\":\"06:00\",\"end\":\"14:00\"}]}")
                .andExpect(status().isOk());

        send(owner, post(records("/disabilities")),
                "{\"degree\":\"MODERATE\",\"startDate\":\"2020-01-01\",\"doctorConsent\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaveYear.additionalDays").value(10))
                .andExpect(jsonPath("$.rights", hasItem(containsString("7 godzin"))))
                .andExpect(jsonPath("$.warnings[*].message", hasItem(containsString("przekraczają 7 godzin"))));
    }

    @Test
    void profileFieldsDriveSeniority() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2024-01-01", null, 1, 1));
        send(owner, put(records("/profile")), "{\"educationLevel\":\"HIGHER\",\"firstJob\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.educationLevel").value("HIGHER"))
                .andExpect(jsonPath("$.leaveYear.fullDays").value(26));
    }

    @Test
    void firstJobExcludesEarlierEmployment() throws Exception {
        send(owner, put(records("/profile")), "{\"firstJob\":true}").andExpect(status().isOk());
        send(owner, post(records("/work-periods")), "{\"kind\":\"EMPLOYMENT\",\"startDate\":\"2015-01-01\","
                + "\"endDate\":\"2016-12-31\",\"documented\":true}").andExpect(status().isConflict());
        send(owner, post(records("/work-periods")), "{\"kind\":\"MANDATE\",\"startDate\":\"2015-01-01\","
                + "\"endDate\":\"2016-12-31\",\"documented\":true}").andExpect(status().isOk());

        send(owner, put(records("/profile")), "{\"firstJob\":false}").andExpect(status().isOk());
        send(owner, post(records("/work-periods")), "{\"kind\":\"ABROAD\",\"startDate\":\"2017-01-01\","
                + "\"endDate\":\"2017-12-31\",\"documented\":true}").andExpect(status().isOk());
        send(owner, put(records("/profile")), "{\"firstJob\":true}").andExpect(status().isConflict());
    }

    @Test
    void workPeriodCountsOnlyWhenDocumented() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2025-01-01", null, 1, 1));
        send(owner, post(records("/work-periods")), "{\"kind\":\"MANDATE\",\"employer\":\"Firma\","
                + "\"startDate\":\"2010-01-01\",\"endDate\":\"2024-12-31\",\"documented\":false}")
                .andExpect(jsonPath("$.leaveYear.fullDays").value(20))
                .andExpect(jsonPath("$.warnings[*].message", hasItem(containsString("zaświadczenie z ZUS"))));
        String id = JsonPath.read(mvc.perform(get(records("")).cookie(owner)).andReturn().getResponse()
                .getContentAsString(), "$.workPeriods[0].id");
        send(owner, put(records("/work-periods/" + id)), "{\"kind\":\"MANDATE\",\"employer\":\"Firma\","
                + "\"startDate\":\"2010-01-01\",\"endDate\":\"2024-12-31\",\"documented\":true}")
                .andExpect(jsonPath("$.leaveYear.fullDays").value(26));
    }

    @Test
    void oldContractDoesNotInventLeaveFromYearsWithoutRecords() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2015-01-01", null, 1, 1))
                .andExpect(jsonPath("$.leaveYear.carriedDays").value(0))
                .andExpect(jsonPath("$.leaveYear.remainingDays").value(26))
                .andExpect(jsonPath("$.warnings[*].message", hasItem(containsString("Uzupełnij urlop"))));

        send(owner, put(records("/leave-opening")), "{\"year\":" + YEAR + ",\"days\":4}")
                .andExpect(jsonPath("$.leaveYear.remainingDays").value(30));
    }

    @Test
    void openingBalanceReplacesPreviousOne() throws Exception {
        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", YEAR + "-01-01", null, 1, 1));
        send(owner, put(records("/leave-opening")), "{\"year\":" + YEAR + ",\"days\":5}")
                .andExpect(jsonPath("$.leaveYear.carriedDays").value(5));
        send(owner, put(records("/leave-opening")), "{\"year\":" + YEAR + ",\"days\":3}")
                .andExpect(jsonPath("$.leaveYear.carriedDays").value(3));
    }

    @Test
    void listShowsContractLeaveAndWorstWarning() throws Exception {
        mvc.perform(get(staff("/records")).cookie(owner))
                .andExpect(jsonPath("$[0].contractKind").isEmpty())
                .andExpect(jsonPath("$[0].worstWarning").value("ERROR"));

        send(owner, post(records("/contracts")), contract("EMPLOYMENT", "INDEFINITE", "2020-01-01", null, 1, 1));
        mvc.perform(get(staff("/records")).cookie(owner))
                .andExpect(jsonPath("$[0].contractKind").value("EMPLOYMENT"))
                .andExpect(jsonPath("$[0].remainingLeave").isNumber());
    }

    @Test
    void sensitiveDataNeedsOwnPermissionNotStaffAccess() throws Exception {
        Cookie manager = worker(createRole("{\"STAFF\":\"EDIT\"}"));
        mvc.perform(get(staff("/records")).cookie(manager)).andExpect(status().isForbidden());
        mvc.perform(get(records("")).cookie(manager)).andExpect(status().isForbidden());
        send(manager, post(records("/disabilities")), "{\"degree\":\"LIGHT\",\"startDate\":\"2020-01-01\",\"doctorConsent\":false}")
                .andExpect(status().isForbidden());
        // Lista pracowników nie zdradza danych kadrowych.
        String list = mvc.perform(get(staff("/employees")).cookie(manager)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("degree", "contract", "birthDate");
    }

    @Test
    void readOnlyHrCannotChangeRecords() throws Exception {
        Cookie hr = worker(createRole("{\"STAFF_RECORDS\":\"VIEW\"}"));
        mvc.perform(get(records("")).cookie(hr)).andExpect(status().isOk());
        send(hr, post(records("/contracts")), contract("B2B", null, "2026-01-01", null, 1, 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordOfOtherCompanyIsOutOfReach() throws Exception {
        String other = createStore();
        mvc.perform(get("/api/stores/" + other + "/staff/records/" + employee).cookie(owner))
                .andExpect(status().isNotFound());
    }

    private String invite() throws Exception {
        String login = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String json = send(owner, post(staff("/employees")), "{\"displayName\":\"Jan " + login + "\",\"email\":\""
                + login + "@sklep.pl\",\"positionId\":\"" + position + "\",\"shifts\":[]}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private static String contract(String kind, String type, String from, String to, int numerator, int denominator) {
        return "{\"kind\":\"" + kind + "\"," + (type == null ? "" : "\"employmentType\":\"" + type + "\",")
                + "\"startDate\":\"" + from + "\"," + (to == null ? "" : "\"endDate\":\"" + to + "\",")
                + "\"fteNumerator\":" + numerator + ",\"fteDenominator\":" + denominator + ",\"fixedTermExempt\":false}";
    }

    private String staff(String suffix) {
        return "/api/stores/" + store + "/staff" + suffix;
    }

    private String records(String suffix) {
        return staff("/records/" + employee + suffix);
    }

    private String createRole(String permissions) throws Exception {
        String json = send(owner, post("/api/settings/roles"),
                "{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie worker(String roleId) throws Exception {
        String username = "konto" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), roleId);
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return login(username, "haslo-pracownika");
    }

    private ResultActions send(Cookie cookie, MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.cookie(cookie).with(csrf());
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
