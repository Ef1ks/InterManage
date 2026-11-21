package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.component.StoreRoleBootstrap;
import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Przejście z ról globalnych na rangi sklepów: rola o nazwie domyślnej rangi oddaje jej uprawnienia, inna staje się
 * rangą tuż nad Pracownikiem, konto dostaje w swoich sklepach rangę ze swojej roli, a stanowisko klucz rangi.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreRoleBootstrapIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StoreRoleBootstrap bootstrap;

    @Test
    void legacyRolesBecomeStoreRanks() throws Exception {
        Cookie owner = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String store = JsonPath.read(mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        String hr = legacyRole("Kadry", "STAFF_RECORDS", "VIEW");
        String station = legacyRole("Stacja " + UUID.randomUUID().toString().substring(0, 6), "SALES", "EDIT");
        String hrUser = legacyUser(hr, store);
        String stationUser = legacyUser(station, store);
        String departments = mvc.perform(post("/api/stores/" + store + "/staff/departments").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kasy\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String department = JsonPath.<List<String>>read(departments, "$[?(@.name == 'Kasy')].id").get(0);
        jdbc.update("""
                insert into staff_positions (id, department_id, name, role_id, created_at)
                values (gen_random_uuid(), ?::uuid, 'Kasjer stacji', ?::uuid, now())""", department, station);

        bootstrap.afterSingletonsInstantiated();

        assertThat(rankKey(hrUser, store)).isEqualTo("HR");
        assertThat(jdbc.queryForObject("""
                select level from store_role_permissions p join store_roles r on r.id = p.store_role_id
                where r.store_id = ?::uuid and r.role_key = 'HR' and p.category = 'STAFF_RECORDS'""",
                String.class, store)).isEqualTo("VIEW");
        assertThat(rankKey(stationUser, store)).isEqualTo("legacy:" + station);
        List<String> order = jdbc.queryForList("""
                select role_key from store_roles where store_id = ?::uuid and deleted_at is null
                  and not admin and not outside_hierarchy order by position""", String.class, store);
        assertThat(order.indexOf("legacy:" + station)).isEqualTo(order.indexOf("EMPLOYEE") - 1);
        assertThat(jdbc.queryForObject("select r.system_key from users u join roles r on r.id = u.role_id"
                + " where u.id = ?::uuid", String.class, stationUser)).isEqualTo("ACCOUNT");
        assertThat(jdbc.queryForObject("select role_key from staff_positions where department_id = ?::uuid",
                String.class, department)).isEqualTo("legacy:" + station);
    }

    private String legacyRole(String name, String category, String level) {
        String id = jdbc.queryForObject("insert into roles (name) values (?) returning id::text", String.class, name);
        jdbc.update("insert into role_permissions (role_id, category, level) values (?::uuid, ?, ?)", id, category,
                level);
        return id;
    }

    private String legacyUser(String roleId, String store) {
        String id = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', 'x', ?::uuid) returning id::text""", String.class,
                "bt" + UUID.randomUUID().toString().substring(0, 8), roleId);
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", id, store);
        return id;
    }

    private String rankKey(String userId, String store) {
        return jdbc.queryForObject("""
                select r.role_key from user_store_roles a join store_roles r on r.id = a.store_role_id
                where a.user_id = ?::uuid and a.store_id = ?::uuid""", String.class, userId, store);
    }
}
