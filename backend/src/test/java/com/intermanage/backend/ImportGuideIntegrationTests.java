package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Instrukcje do raportów w Imporcie danych: zapis przez właściciela (znaczniki), odczyt z IMPORTS:VIEW. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ImportGuideIntegrationTests {

    private static final String PATH = "/api/settings/import-guides/";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        jdbc.update("update import_guides set deleted_at = now() where kind = 'SALES' and deleted_at is null");
    }

    @Test
    void ownerWritesReplacesAndClearsGuideWithMarkers() throws Exception {
        save(owner, "{\"content\":\"  Raporty > Sprzedaż  \",\"folderPath\":\"N:\\\\raporty\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'SALES')].content").value("Raporty > Sprzedaż"))
                .andExpect(jsonPath("$[?(@.kind == 'SALES')].folderPath").value("N:\\raporty"));

        // Ta sama treść niczego nie dopisuje, inna daje znacznik poprzedniej.
        save(owner, "{\"content\":\"Raporty > Sprzedaż\",\"folderPath\":\"N:\\\\raporty\"}").andExpect(status().isOk());
        assertThat(count(false)).isEqualTo(1);
        save(owner, "{\"content\":\"Nowa instrukcja\",\"folderPath\":null}")
                .andExpect(jsonPath("$[?(@.kind == 'SALES')].content").value("Nowa instrukcja"));
        assertThat(count(false)).isEqualTo(1);

        save(owner, "{\"content\":\" \",\"folderPath\":\"\"}")
                .andExpect(jsonPath("$[?(@.kind == 'SALES')]", hasSize(0)));
        assertThat(count(false)).isZero();
    }

    @Test
    void employeeReadsButCannotWrite() throws Exception {
        save(owner, "{\"content\":\"Raporty > Sprzedaż\"}").andExpect(status().isOk());
        Cookie viewer = employee("{\"IMPORTS\":\"VIEW\"}");

        mvc.perform(get("/api/import-guides").cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'SALES')].content").value("Raporty > Sprzedaż"));
        save(employee("{\"IMPORTS\":\"EDIT\"}"), "{\"content\":\"x\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/import-guides").cookie(employee("{\"SALES\":\"VIEW\"}")))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions save(Cookie user, String body) throws Exception {
        return mvc.perform(put(PATH + "SALES").cookie(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count(boolean deleted) {
        return jdbc.queryForObject("select count(*) from import_guides where kind = 'SALES' and deleted_at is "
                + (deleted ? "not null" : "null"), Integer.class);
    }

    private Cookie employee(String permissions) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storeId = createStore();
        String username = "ig" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, storeId);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
