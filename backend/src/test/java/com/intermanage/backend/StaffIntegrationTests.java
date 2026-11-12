package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import com.intermanage.backend.auth.component.LoginNames;
import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Pracownicy: działy, stanowiska z rolą, zaproszenia z loginem z imienia i nazwiska, stałe godziny i koniec pracy. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StaffIntegrationTests {

    private static final String WEEKDAY_6_14 = "{\"dayOfWeek\":\"MONDAY\",\"start\":\"06:00\",\"end\":\"14:00\"}";
    private static final String WEEKDAY_7_15 = "{\"dayOfWeek\":\"MONDAY\",\"start\":\"07:00\",\"end\":\"15:00\"}";
    private static final String SUNDAY_8_14 = "{\"dayOfWeek\":\"SUNDAY\",\"start\":\"08:00\",\"end\":\"14:00\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RecordingAccountMailer mailer;

    private Cookie owner;
    private String store;
    private String nip;
    private String cashierRole;
    private String managerRole;
    private String department;
    private String cashier;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        store = createStore();
        cashierRole = createRole("{\"SALES\":\"VIEW\"}");
        managerRole = createRole("{\"STAFF\":\"EDIT\",\"SALES\":\"EDIT\"}");
        department = createDepartment("Kasy");
        cashier = createPosition(department, "Kasjer", cashierRole, List.of(WEEKDAY_6_14, WEEKDAY_7_15, SUNDAY_8_14));
    }

    @Test
    void loginIsFirstLetterAndSurnameWithoutPolishLetters() {
        assertThat(LoginNames.base("Jan Kowalski")).isEqualTo("jkowalski");
        assertThat(LoginNames.base("  Łukasz   Żółć ")).isEqualTo("lzolc");
        assertThat(LoginNames.base("Anna Maria Nowak-Kowalska")).isEqualTo("anowakkowalska");
        assertThat(LoginNames.base("Madonna")).isEqualTo("madonna");
        assertThat(LoginNames.base("Al")).isEmpty();
        assertThat(LoginNames.base("???")).isEmpty();
    }

    @Test
    void invitedEmployeeGetsLoginRoleStoreAndMail() throws Exception {
        String surname = surname();
        String json = invite("Jan " + surname, cashier, WEEKDAY_7_15)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("j" + surname))
                .andExpect(jsonPath("$.departmentName").value("Kasy"))
                .andExpect(jsonPath("$.positionName").value("Kasjer"))
                .andExpect(jsonPath("$.pendingActivation").value(true))
                .andExpect(jsonPath("$.shifts", hasSize(1)))
                .andExpect(jsonPath("$.shifts[0].start").value("07:00"))
                .andReturn().getResponse().getContentAsString();

        String userId = JsonPath.read(json, "$.userId");
        assertThat(jdbc.queryForObject("select role_id::text from users where id = ?::uuid", String.class, userId))
                .isEqualTo(cashierRole);
        assertThat(jdbc.queryForObject("select store_id::text from user_stores where user_id = ?::uuid", String.class,
                userId)).isEqualTo(store);
        assertThat(mailer.lastTokenFor(email(surname))).isNotBlank();
    }

    @Test
    void sameNameGetsNextNumberAndEndedAccountKeepsItsLogin() throws Exception {
        String surname = surname();
        String first = id(invite("Jan " + surname, cashier).andExpect(status().isCreated()));
        send(owner, delete(path("/employees/" + first)), null).andExpect(status().isNoContent());

        mvc.perform(get(path("/login-suggestion")).cookie(owner).param("name", "Janina " + surname))
                .andExpect(jsonPath("$.username").value("j" + surname + "2"));
        inviteAs("Janina " + surname, cashier, "druga-" + surname + "@sklep.pl")
                .andExpect(jsonPath("$.username").value("j" + surname + "2"));
        inviteAs("Jerzy " + surname, cashier, "trzeci-" + surname + "@sklep.pl")
                .andExpect(jsonPath("$.username").value("j" + surname + "3"));
    }

    @Test
    void loginTypedByHandIsKeptButMustBeFree() throws Exception {
        String login = "reczny" + surname();
        String body = "{\"displayName\":\"Jan Reczny\",\"email\":\"" + login + "@sklep.pl\",\"username\":\"" + login
                + "\",\"positionId\":\"" + cashier + "\",\"shifts\":[]}";
        send(owner, post(path("/employees")), body).andExpect(jsonPath("$.username").value(login));
        send(owner, post(path("/employees")), body.replace(login + "@", "inny" + login + "@"))
                .andExpect(status().isConflict());
    }

    @Test
    void employeeMayHaveHoursOutsideThePosition() throws Exception {
        String custom = "{\"dayOfWeek\":\"MONDAY\",\"start\":\"22:00\",\"end\":\"06:00\"}";
        invite("Noc " + surname(), cashier, custom)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shifts[0].start").value("22:00"))
                .andExpect(jsonPath("$.shifts[0].end").value("06:00"));
    }

    @Test
    void savingHoursMarksRemovedOnesInsteadOfDeleting() throws Exception {
        String surname = surname();
        String employee = id(invite("Jan " + surname, cashier, WEEKDAY_6_14, SUNDAY_8_14));
        update(employee, cashier, WEEKDAY_6_14, WEEKDAY_7_15)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shifts[*].start", contains("06:00", "07:00")));

        assertThat(jdbc.queryForObject("""
                select count(*) from staff_shifts where employee_id = ?::uuid and deleted_at is not null""",
                Long.class, employee)).isEqualTo(1);
    }

    @Test
    void positionDecidesTheAccountRole() throws Exception {
        String manager = createPosition(department, "Kierownik kas", managerRole, List.of());
        String surname = surname();
        String json = invite("Jan " + surname, cashier).andReturn().getResponse().getContentAsString();
        String employee = JsonPath.read(json, "$.id");
        String userId = JsonPath.read(json, "$.userId");

        update(employee, manager).andExpect(status().isOk());
        assertThat(role(userId)).isEqualTo(managerRole);

        // Zmiana roli stanowiska od razu zmienia uprawnienia jego pracowników.
        String other = createRole("{\"INVENTORY\":\"VIEW\"}");
        send(owner, put(path("/positions/" + manager)), position(department, "Kierownik kas", other, List.of()))
                .andExpect(status().isOk());
        assertThat(role(userId)).isEqualTo(other);

        // W Użytkownikach roli takiego konta nie da się zmienić.
        send(owner, put("/api/settings/users/" + userId), "{\"displayName\":\"Jan " + surname + "\",\"email\":\""
                + email(surname) + "\",\"roleId\":\"" + cashierRole + "\",\"enabled\":true}")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/settings/users").cookie(owner))
                .andExpect(jsonPath("$[?(@.id == '" + userId + "')].positionName").value("Kierownik kas"));
    }

    @Test
    void employeeWithoutAccountHasOnlyNameAndPosition() throws Exception {
        String surname = surname();
        String employee = id(send(owner, post(path("/employees/without-account")),
                "{\"displayName\":\" Ewa " + surname + " \",\"positionId\":\"" + cashier + "\",\"shifts\":[]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasAccount").value(false))
                .andExpect(jsonPath("$.userId").isEmpty())
                .andExpect(jsonPath("$.username").isEmpty())
                .andExpect(jsonPath("$.displayName").value("Ewa " + surname))
                .andExpect(jsonPath("$.positionName").value("Kasjer")));
        assertThat(jdbc.queryForObject("select count(*) from users where display_name = ?", Integer.class,
                "Ewa " + surname)).isZero();

        send(owner, put(path("/employees/" + employee)),
                "{\"displayName\":\"Ewa Nowa " + surname + "\",\"email\":null,\"positionId\":\"" + cashier
                        + "\",\"shifts\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ewa Nowa " + surname));
        send(owner, post(path("/employees/" + employee + "/invitation")), null).andExpect(status().isConflict());

        send(owner, delete(path("/employees/" + employee)), null).andExpect(status().isNoContent());
        send(owner, post(path("/employees/" + employee + "/restore")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").isEmpty());
        mvc.perform(get(path("/employees")).cookie(owner))
                .andExpect(jsonPath("$[?(@.id == '" + employee + "')].displayName").value("Ewa Nowa " + surname));
    }

    @Test
    void endingEmploymentBlocksAccountAndRestoreBringsItBack() throws Exception {
        Cookie worker = worker(cashierRole);
        String userId = jdbc.queryForObject("""
                select id::text from users order by created_at desc limit 1""", String.class);
        String employee = id(send(owner, post(path("/employees/existing")),
                "{\"userId\":\"" + userId + "\",\"positionId\":\"" + cashier + "\",\"shifts\":[]}")
                .andExpect(status().isCreated()));

        send(owner, delete(path("/employees/" + employee)), null).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(worker)).andExpect(status().isUnauthorized());
        mvc.perform(get(path("/employees")).cookie(owner))
                .andExpect(jsonPath("$[0].endedAt").isNotEmpty());

        send(owner, post(path("/employees/" + employee + "/restore")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").isEmpty());
        mvc.perform(get("/api/auth/me").cookie(worker)).andExpect(status().isOk());
    }

    @Test
    void existingAccountKeepsOwnerRole() throws Exception {
        String ownerId = jdbc.queryForObject("select id::text from users where username = 'wlasciciel'", String.class);
        String employee = id(send(owner, post(path("/employees/existing")),
                "{\"userId\":\"" + ownerId + "\",\"positionId\":\"" + cashier + "\",\"shifts\":[]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerAccount").value(true)));
        assertThat(jdbc.queryForObject("""
                select r.owner from users u join roles r on r.id = u.role_id where u.id = ?::uuid""", Boolean.class,
                ownerId)).isTrue();
        send(owner, delete(path("/employees/" + employee)), null).andExpect(status().isConflict());
        jdbc.update("delete from staff_employees where id = ?::uuid", employee);
    }

    @Test
    void personWithStaffEditCannotGrantMoreThanOwnPermissions() throws Exception {
        String limited = createRole("{\"STAFF\":\"EDIT\"}");
        Cookie manager = worker(limited);
        String boss = createPosition(department, "Kierownik", managerRole, List.of());
        String surname = surname();

        // Stanowisko z większą rolą: ani go nie nada, ani nie założy, ani nie zmieni nim czyjejś roli.
        send(manager, post(path("/employees")), employeeBody("Jan " + surname, boss, email(surname)))
                .andExpect(status().isForbidden());
        send(manager, post(path("/positions")), position(department, "Inny", managerRole, List.of()))
                .andExpect(status().isForbidden());
        send(manager, put(path("/positions/" + cashier)), position(department, "Kasjer", managerRole, List.of()))
                .andExpect(status().isForbidden());

        // Konta z większą rolą nie zmieni, np. podmieniając adres na swój.
        String bossEmployee = id(invite("Jan " + surname, boss));
        send(manager, put(path("/employees/" + bossEmployee)), "{\"displayName\":\"Jan\",\"email\":\"moj@sklep.pl\","
                + "\"positionId\":\"" + boss + "\",\"shifts\":[]}")
                .andExpect(status().isForbidden());

        // Stanowisko z rolą w ramach własnych uprawnień jest w porządku.
        String cheap = createRole("{}");
        String allowed = createPosition(department, "Stażysta", cheap, List.of());
        String other = surname();
        send(manager, post(path("/employees")), employeeBody("Ewa " + other, allowed, email(other)))
                .andExpect(status().isCreated());
    }

    @Test
    void employeeMovesToAnotherStoreOfTheCompany() throws Exception {
        String sister = sisterStore();
        String position = createPosition(department, "Pracownik sali", managerRole, List.of());
        String surname = surname();
        String json = invite("Jan " + surname, cashier, WEEKDAY_6_14).andReturn().getResponse().getContentAsString();
        String employee = JsonPath.read(json, "$.id");
        String userId = JsonPath.read(json, "$.userId");

        mvc.perform(get(path("/company-stores")).cookie(owner))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(store, sister)));
        send(owner, post(path("/employees/" + employee + "/transfer")), transfer(sister, position, WEEKDAY_7_15))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("j" + surname))
                .andExpect(jsonPath("$.positionName").value("Pracownik sali"))
                .andExpect(jsonPath("$.storeId").value(sister))
                .andExpect(jsonPath("$.shifts[*].start", contains("07:00")));

        // Pracownik należy do firmy: lista jest wspólna, a przy nim jest sklep, w którym pracuje.
        mvc.perform(get(path("/employees")).cookie(owner)).andExpect(jsonPath("$[0].storeId").value(sister));
        assertThat(role(userId)).isEqualTo(managerRole);
        assertThat(jdbc.queryForList("select store_id::text from user_stores where user_id = ?::uuid", String.class,
                userId)).containsExactly(sister);
        assertThat(jdbc.queryForObject("select count(*) from staff_employee_moves "
                + "where employee_id = ?::uuid and from_store_id = ?::uuid and to_store_id = ?::uuid",
                Long.class, employee, store, sister)).isEqualTo(1);

        // Cofnięcie to przeniesienie z powrotem.
        send(owner, post("/api/stores/" + sister + "/staff/employees/" + employee + "/transfer"),
                transfer(store, cashier, WEEKDAY_6_14))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionName").value("Kasjer"));
        assertThat(role(userId)).isEqualTo(cashierRole);
    }

    @Test
    void transferKeepsOtherStoreAccess() throws Exception {
        String sister = sisterStore();
        String third = createStore();
        String json = invite("Jan " + surname(), cashier).andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(json, "$.userId");
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, third);

        send(owner, post(path("/employees/" + JsonPath.read(json, "$.id") + "/transfer")), transfer(sister, cashier))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("select store_id::text from user_stores where user_id = ?::uuid", String.class,
                userId)).containsExactlyInAnyOrder(sister, third);
    }

    @Test
    void transferOnlyWithinCompany() throws Exception {
        String employee = id(invite("Jan " + surname(), cashier));

        // Sklep bez NIP-u nie ma firmy.
        String noNip = createStore();
        send(owner, post(path("/employees/" + employee + "/transfer")), transfer(noNip, cashier))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path("/company-stores")).cookie(owner)).andExpect(jsonPath("$[*].id", contains(store)));

        sisterStore();
        String other = createStore();
        jdbc.update("update stores set nip = ? where id = ?::uuid", randomNip(), other);
        send(owner, post(path("/employees/" + employee + "/transfer")), transfer(other, cashier))
                .andExpect(status().isBadRequest());
        send(owner, post(path("/employees/" + employee + "/transfer")), transfer(store, cashier))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transferNeedsAccessToTargetStore() throws Exception {
        String sister = sisterStore();
        String employee = id(invite("Jan " + surname(), cashier));
        Cookie manager = worker(managerRole);

        mvc.perform(get(path("/company-stores")).cookie(manager)).andExpect(jsonPath("$[*].id", contains(store)));
        send(manager, post(path("/employees/" + employee + "/transfer")), transfer(sister, cashier))
                .andExpect(status().isForbidden());
    }

    @Test
    void departmentsAreSharedByStoresOfTheCompany() throws Exception {
        String sister = sisterStore();
        mvc.perform(get("/api/stores/" + sister + "/staff/structure").cookie(owner))
                .andExpect(jsonPath("$[*].name", contains("Kasy")))
                .andExpect(jsonPath("$[0].positions[0].id").value(cashier));

        // Pracownika sklepu siostrzanego zakłada się na tym samym stanowisku.
        String surname = surname();
        send(owner, post("/api/stores/" + sister + "/staff/employees"),
                employeeBody("Jan " + surname, cashier, email(surname)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(sister));
        mvc.perform(get(path("/structure")).cookie(owner))
                .andExpect(jsonPath("$[0].positions[0].employeeCount").value(1));

        // Nazwa jest unikalna w całej firmie.
        send(owner, post("/api/stores/" + sister + "/staff/departments"), "{\"name\":\"Kasy\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void companyStoresAlwaysContainStoreFromAddress() throws Exception {
        String sister = sisterStore();
        jdbc.update("update stores set active = false where id = ?::uuid", store);
        mvc.perform(get(path("/company-stores")).cookie(owner))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(store, sister)));
    }

    @Test
    void positionHoursAreSeparateForEachStore() throws Exception {
        String sister = sisterStore();
        String friday = "{\"dayOfWeek\":\"FRIDAY\",\"start\":\"10:00\",\"end\":\"18:00\"}";

        send(owner, put("/api/stores/" + sister + "/staff/positions/" + cashier),
                position(department, "Kasjer", cashierRole, List.of(friday)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].positions[0].shifts[*].dayOfWeek", contains("FRIDAY")));

        // Sklep, w którym stanowisko założono, ma dalej swoje godziny.
        mvc.perform(get(path("/structure")).cookie(owner))
                .andExpect(jsonPath("$[0].positions[0].shifts", hasSize(3)));
    }

    @Test
    void everyDayMayHaveDifferentHours() throws Exception {
        String tuesday = "{\"dayOfWeek\":\"TUESDAY\",\"start\":\"12:00\",\"end\":\"20:00\"}";
        invite("Jan " + surname(), cashier, WEEKDAY_6_14, tuesday, SUNDAY_8_14)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shifts[*].dayOfWeek", contains("MONDAY", "SUNDAY", "TUESDAY")));

        String oldType = "{\"dayOfWeek\":\"WEEKDAY\",\"start\":\"06:00\",\"end\":\"14:00\"}";
        send(owner, post(path("/positions")), position(department, "Zły", cashierRole, List.of(oldType)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hrSeesAllOwnStoresOfTheCompanyAndNoOthers() throws Exception {
        String sister = sisterStore();
        String third = createStore();
        jdbc.update("update stores set nip = ? where id = ?::uuid", nip, third);
        String inSister = id(send(owner, post("/api/stores/" + sister + "/staff/employees"),
                employeeBody("Ewa " + surname(), cashier, surname() + "@sklep.pl")));
        String inThird = id(send(owner, post("/api/stores/" + third + "/staff/employees"),
                employeeBody("Ola " + surname(), cashier, surname() + "@sklep.pl")));

        Cookie hr = worker(managerRole);
        String hrId = jdbc.queryForObject("select u.id::text from users u join user_stores s on s.user_id = u.id "
                + "where s.store_id = ?::uuid and u.username like 'konto%' order by u.created_at desc limit 1",
                String.class, store);
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", hrId, sister);

        mvc.perform(get(path("/employees")).cookie(hr))
                .andExpect(jsonPath("$[*].id", contains(inSister)));
        mvc.perform(get(path("/company-stores")).cookie(hr))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(store, sister)));

        // Pracownika z przypisanego sklepu zmienia z każdego ze swoich, ze sklepu bez dostępu nie.
        String update = "{\"displayName\":\"Ewa\",\"email\":\"ewa" + surname() + "@sklep.pl\",\"positionId\":\""
                + cashier + "\",\"shifts\":[]}";
        send(hr, put(path("/employees/" + inSister)), update).andExpect(status().isOk());
        send(hr, put(path("/employees/" + inThird)), update).andExpect(status().isNotFound());
    }

    @Test
    void storeGettingNipBringsDepartmentsToTheCompany() throws Exception {
        String number = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, store);
        String body = "{\"number\":\"" + number + "\",\"name\":\"Sklep\",\"nip\":\"7812022538\",\"active\":true}";
        send(owner, put("/api/settings/stores/" + store), body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select company_key from staff_departments where id = ?::uuid", String.class,
                department)).isEqualTo("7812022538");
        mvc.perform(get(path("/structure")).cookie(owner)).andExpect(jsonPath("$[*].id", hasItem(department)));
        jdbc.update("update staff_departments set company_key = 'store:' || ? where id = ?::uuid", store, department);
        jdbc.update("update stores set nip = null where id = ?::uuid", store);
    }

    @Test
    void storeWithEmployeesCannotChangeCompany() throws Exception {
        sisterStore();
        invite("Jan " + surname(), cashier).andExpect(status().isCreated());
        String number = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, store);
        send(owner, put("/api/settings/stores/" + store),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep\",\"active\":true}")
                .andExpect(status().isConflict());
    }

    @Test
    void ownerRoleCannotBeUsedOnPosition() throws Exception {
        String ownerRole = jdbc.queryForObject("select id::text from roles where owner", String.class);
        send(owner, post(path("/positions")), position(department, "Szef", ownerRole, List.of()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void occupiedPositionRoleAndDepartmentCannotBeRemoved() throws Exception {
        String employee = id(invite("Jan " + surname(), cashier));

        send(owner, delete(path("/positions/" + cashier)), null).andExpect(status().isConflict());
        send(owner, delete(path("/departments/" + department)), null).andExpect(status().isConflict());
        send(owner, delete("/api/settings/roles/" + cashierRole), null).andExpect(status().isConflict());

        send(owner, delete(path("/employees/" + employee)), null).andExpect(status().isNoContent());
        send(owner, delete(path("/positions/" + cashier)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].positions", hasSize(0)));
        send(owner, post(path("/employees/" + employee + "/restore")), null).andExpect(status().isConflict());
        send(owner, post(path("/positions/" + cashier + "/restore")), null)
                .andExpect(jsonPath("$[0].positions[0].shifts", hasSize(3)));
    }

    @Test
    void namesAreUniqueAmongActiveOnes() throws Exception {
        send(owner, post(path("/departments")), "{\"name\":\"kasy\"}").andExpect(status().isConflict());
        send(owner, post(path("/positions")), position(department, "KASJER", cashierRole, List.of()))
                .andExpect(status().isConflict());
        String deleted = createDepartment("Garmaż");
        send(owner, delete(path("/departments/" + deleted)), null).andExpect(status().isOk());
        createDepartment("Garmaż");
        send(owner, post(path("/departments/" + deleted + "/restore")), null).andExpect(status().isConflict());
    }

    @Test
    void otherStoresDataIsOutOfReach() throws Exception {
        String other = createStore();
        Cookie worker = worker(cashierRole);
        mvc.perform(get("/api/stores/" + other + "/staff/employees").cookie(worker)).andExpect(status().isForbidden());
        mvc.perform(get(path("/employees")).cookie(worker)).andExpect(status().isForbidden());

        // Stanowisko z innego sklepu nie pasuje do pracownika tego sklepu.
        String surname = surname();
        send(owner, post("/api/stores/" + other + "/staff/employees"), employeeBody("Jan " + surname, cashier,
                email(surname))).andExpect(status().isBadRequest());
    }

    @Test
    void shiftMustHaveDifferentStartAndEnd() throws Exception {
        String same = "{\"dayOfWeek\":\"MONDAY\",\"start\":\"06:00\",\"end\":\"06:00\"}";
        send(owner, post(path("/positions")), position(department, "Zły", cashierRole, List.of(same)))
                .andExpect(status().isBadRequest());
        String wrong = "{\"dayOfWeek\":\"MONDAY\",\"start\":\"25:00\",\"end\":\"06:00\"}";
        send(owner, post(path("/positions")), position(department, "Zły", cashierRole, List.of(wrong)))
                .andExpect(status().isBadRequest());
    }

    /** Bieżący sklep dostaje firmę razem ze swoimi działami, a obok niego powstaje nowy sklep tej firmy. */
    private String sisterStore() throws Exception {
        nip = randomNip();
        String sister = createStore();
        jdbc.update("update stores set nip = ? where id in (?::uuid, ?::uuid)", nip, store, sister);
        jdbc.update("update staff_departments set company_key = ? where company_key = 'store:' || ?", nip, store);
        return sister;
    }

    private static String randomNip() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private static String transfer(String storeId, String positionId, String... shifts) {
        return "{\"storeId\":\"" + storeId + "\",\"positionId\":\"" + positionId + "\",\"shifts\":["
                + String.join(",", shifts) + "]}";
    }

    private ResultActions invite(String name, String positionId, String... shifts) throws Exception {
        String surname = name.substring(name.indexOf(' ') + 1);
        return send(owner, post(path("/employees")), employeeBody(name, positionId, email(surname), shifts));
    }

    private ResultActions inviteAs(String name, String positionId, String email) throws Exception {
        return send(owner, post(path("/employees")), employeeBody(name, positionId, email));
    }

    private ResultActions update(String employee, String positionId, String... shifts) throws Exception {
        String name = jdbc.queryForObject("""
                select u.display_name from staff_employees e join users u on u.id = e.user_id
                where e.id = ?::uuid""", String.class, employee);
        return send(owner, put(path("/employees/" + employee)), "{\"displayName\":\"" + name
                + "\",\"email\":\"zmieniony-" + UUID.randomUUID() + "@sklep.pl\",\"positionId\":\"" + positionId
                + "\",\"shifts\":[" + String.join(",", shifts) + "]}");
    }

    private static String employeeBody(String name, String positionId, String email, String... shifts) {
        return "{\"displayName\":\"" + name + "\",\"email\":\"" + email + "\",\"positionId\":\"" + positionId
                + "\",\"shifts\":[" + String.join(",", shifts) + "]}";
    }

    private static String position(String departmentId, String name, String roleId, List<String> shifts) {
        return "{\"departmentId\":\"" + departmentId + "\",\"name\":\"" + name + "\",\"roleId\":\"" + roleId
                + "\",\"shifts\":[" + String.join(",", shifts) + "]}";
    }

    private String createDepartment(String name) throws Exception {
        String json = send(owner, post(path("/departments")), "{\"name\":\"" + name + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(json, "$[?(@.name == '" + name + "')].id").getFirst();
    }

    private String createPosition(String departmentId, String name, String roleId, List<String> shifts)
            throws Exception {
        String json = send(owner, post(path("/positions")), position(departmentId, name, roleId, shifts))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(json, "$[*].positions[?(@.name == '" + name + "')].id").getFirst();
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

    /** Aktywne konto z dostępem do sklepu. */
    private Cookie worker(String roleId) throws Exception {
        String username = "konto" + surname();
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), roleId);
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return login(username, "haslo-pracownika");
    }

    private String role(String userId) {
        return jdbc.queryForObject("select role_id::text from users where id = ?::uuid", String.class, userId);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions send(Cookie cookie, MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.cookie(cookie).with(csrf());
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String path(String suffix) {
        return "/api/stores/" + store + "/staff" + suffix;
    }

    private static String email(String surname) {
        return surname + "@sklep.pl";
    }

    private static String surname() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
