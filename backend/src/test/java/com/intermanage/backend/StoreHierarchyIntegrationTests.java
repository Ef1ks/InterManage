package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Hierarchia sklepu: domyślne rangi w każdym sklepie, uprawnienia według sklepu z adresu, Aderon bez ustawień firmy,
 * zmiany tylko pod własną rangą, zlecanie zadań w dół i przesłanie hierarchii sklepowi zaprzyjaźnionemu.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreHierarchyIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String store;

    @BeforeEach
    void setUp() throws Exception {
        owner = login("wlasciciel", "haslo-wlasciciela");
        store = createStore();
    }

    @Test
    void everyNewStoreGetsDefaultRanks() throws Exception {
        mvc.perform(get(roles(store)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.roles[*].name", contains("Aderon", "Dyrektor", "Kierownik",
                        "Zastępca kierownika", "Lider działu", "Zarządca produkcji", "Informatyk", "Pracownik",
                        "Księgowość", "Kadry")))
                .andExpect(jsonPath("$.roles[0].admin").value(true))
                .andExpect(jsonPath("$.roles[0].permissions.STAFF_RECORDS").value("EDIT"))
                .andExpect(jsonPath("$.roles[?(@.outsideHierarchy == true)].key", contains("ACCOUNTING", "HR")));
    }

    @Test
    void permissionsComeFromTheRankInTheStoreOfTheRequest() throws Exception {
        String other = createStore();
        String user = user();
        join(user, store, "MANAGER");
        join(user, other, "EMPLOYEE");
        Cookie session = login(username(user), "haslo-pracownika");

        mvc.perform(get("/api/stores/" + store + "/imports/products").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/stores/" + other + "/imports/products").cookie(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.storeRoles['" + store + "']").value("Kierownik"))
                .andExpect(jsonPath("$.storePermissions['" + store + "'].IMPORTS").value("EDIT"))
                .andExpect(jsonPath("$.storePermissions['" + other + "'].IMPORTS").doesNotExist())
                .andExpect(jsonPath("$.storePermissions['" + other + "'].SALES").value("VIEW"));
    }

    @Test
    void aderonHasEverythingInStoreButNotCompanySettings() throws Exception {
        String other = createStore();
        String user = user();
        join(user, store, "ADERON");
        Cookie aderon = login(username(user), "haslo-pracownika");

        mvc.perform(get("/api/stores/" + store + "/staff/records").cookie(aderon)).andExpect(status().isOk());
        mvc.perform(get("/api/stores/" + store + "/agents").cookie(aderon)).andExpect(status().isOk());
        mvc.perform(get("/api/settings/users").cookie(aderon)).andExpect(status().isForbidden());
        mvc.perform(get("/api/stores/" + other + "/imports/products").cookie(aderon))
                .andExpect(status().isForbidden());
    }

    @Test
    void higherRankChangesOnlyRanksBelowItsOwn() throws Exception {
        String user = user();
        join(user, store, "DIRECTOR");
        Cookie director = login(username(user), "haslo-pracownika");

        String employee = rank(store, "EMPLOYEE");
        String manager = rank(store, "MANAGER");
        send(director, put(roles(store) + "/" + employee),
                "{\"name\":\"Pracownik sklepu\",\"permissions\":{\"SALES\":\"VIEW\",\"DOCUMENTS\":\"VIEW\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.key == 'EMPLOYEE')].name", contains("Pracownik sklepu")));
        send(director, put(roles(store) + "/" + rank(store, "DIRECTOR")),
                "{\"name\":\"Dyrektor\",\"permissions\":{}}").andExpect(status().isForbidden());
        send(director, put(roles(store) + "/" + rank(store, "ADERON")),
                "{\"name\":\"Szef\",\"permissions\":{}}").andExpect(status().isForbidden());

        // Nowa ranga staje nad Pracownikiem.
        send(director, post(roles(store)), "{\"name\":\"Kasjer\",\"permissions\":{\"SALES\":\"VIEW\"}}")
                .andExpect(status().isCreated());
        List<String> order = hierarchy(store);
        assertThat(order.indexOf(id(store, "Kasjer"))).isEqualTo(order.indexOf(employee) - 1);

        // Przestawia tylko rangi pod sobą.
        List<String> swapped = new ArrayList<>(order);
        swapped.remove(manager);
        swapped.add(swapped.size() - 1, manager);
        send(director, put(roles(store) + "/order"), orderJson(swapped)).andExpect(status().isOk());
        List<String> above = new ArrayList<>(order);
        above.remove(manager);
        above.add(0, manager);
        send(director, put(roles(store) + "/order"), orderJson(above)).andExpect(status().isForbidden());

        // Ranga z kontami nie znika.
        send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                roles(store) + "/" + rank(store, "DIRECTOR")), null).andExpect(status().isConflict());
    }

    @Test
    void tasksGoDownTheHierarchy() throws Exception {
        String boss = user();
        join(boss, store, "MANAGER");
        String worker = user();
        join(worker, store, "EMPLOYEE");
        String aderon = user();
        join(aderon, store, "ADERON");
        Cookie manager = login(username(boss), "haslo-pracownika");

        mvc.perform(get("/api/stores/" + store + "/tasks/assignees").cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(worker)))
                .andExpect(jsonPath("$[*].id", not(hasItem(aderon))));
        send(manager, post("/api/stores/" + store + "/tasks"),
                "{\"title\":\"Policz kawę\",\"priority\":\"NORMAL\",\"assigneeId\":\"" + worker + "\"}")
                .andExpect(status().isCreated());
        send(manager, post("/api/stores/" + store + "/tasks"),
                "{\"title\":\"Policz kawę\",\"priority\":\"NORMAL\",\"assigneeId\":\"" + aderon + "\"}")
                .andExpect(status().isBadRequest());

        // Najniższa ranga nie ma komu zlecać.
        Cookie employee = login(username(worker), "haslo-pracownika");
        mvc.perform(get("/api/stores/" + store + "/tasks/assignees").cookie(employee))
                .andExpect(status().isForbidden());
    }

    @Test
    void partnerStoreTakesOverHierarchyAndCanUndoIt() throws Exception {
        String partner = createStore();
        String invitation = id(send(owner, post("/api/stores/" + store + "/partners"),
                "{\"storeId\":\"" + partner + "\"}").andExpect(status().isOk()), "$.partners[0].id");
        send(owner, post("/api/stores/" + partner + "/partners/" + invitation + "/accept"), null)
                .andExpect(status().isOk());

        send(owner, put(roles(store) + "/" + rank(store, "MANAGER")),
                "{\"name\":\"Szef zmiany\",\"permissions\":{\"SALES\":\"EDIT\"}}").andExpect(status().isOk());
        send(owner, post(roles(store)), "{\"name\":\"Kasjer\",\"permissions\":{\"SALES\":\"VIEW\"}}")
                .andExpect(status().isCreated());
        send(owner, post(roles(store) + "/offers"), "{\"storeIds\":[\"" + partner + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outgoing[0].storeId").value(partner));

        String offer = JsonPath.read(mvc.perform(get(roles(partner)).cookie(owner)).andReturn().getResponse()
                .getContentAsString(), "$.incoming[0].id");
        send(owner, post(roles(partner) + "/offers/" + offer + "/accept"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.key == 'MANAGER')].name", contains("Szef zmiany")))
                .andExpect(jsonPath("$.roles[?(@.key == 'MANAGER')].permissions.SALES", contains("EDIT")))
                .andExpect(jsonPath("$.roles[*].name", hasItem("Kasjer")))
                .andExpect(jsonPath("$.incoming").isEmpty());

        send(owner, post(roles(partner) + "/offers/" + offer + "/undo"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.key == 'MANAGER')].name", contains("Kierownik")))
                .andExpect(jsonPath("$.roles[*].name", not(hasItem("Kasjer"))));

        // Sklepowi spoza zaprzyjaźnionych hierarchii się nie przesyła.
        send(owner, post(roles(store) + "/offers"), "{\"storeIds\":[\"" + createStore() + "\"]}")
                .andExpect(status().isBadRequest());
    }

    private String roles(String storeId) {
        return "/api/stores/" + storeId + "/roles";
    }

    private String rank(String storeId, String key) {
        return TestRanks.defaultRank(jdbc, storeId, key);
    }

    private String id(String storeId, String name) {
        return jdbc.queryForObject("select id::text from store_roles where store_id = ?::uuid and name = ?"
                + " and deleted_at is null", String.class, storeId, name);
    }

    private List<String> hierarchy(String storeId) {
        return jdbc.queryForList("select id::text from store_roles where store_id = ?::uuid and deleted_at is null"
                + " and not admin and not outside_hierarchy order by position", String.class, storeId);
    }

    private static String orderJson(List<String> ids) {
        return "{\"roleIds\":[\"" + String.join("\",\"", ids) + "\"]}";
    }

    private String user() {
        String username = "hr" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, (select id from roles where system_key = 'ACCOUNT')) returning id::text""",
                String.class, username, passwordEncoder.encode("haslo-pracownika"));
    }

    private void join(String userId, String storeId, String key) {
        TestRanks.join(jdbc, userId, storeId);
        TestRanks.assign(jdbc, userId, storeId, rank(storeId, key));
    }

    private String username(String userId) {
        return jdbc.queryForObject("select username from users where id = ?::uuid", String.class, userId);
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        return id(send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
                .andExpect(status().isCreated()), "$.id");
    }

    private static String id(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions send(Cookie session, MockHttpServletRequestBuilder request, String json) throws Exception {
        request = request.cookie(session).with(csrf());
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
