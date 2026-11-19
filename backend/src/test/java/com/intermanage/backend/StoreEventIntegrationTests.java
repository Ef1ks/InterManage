package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.activity.component.StoreEventBuffer;
import com.intermanage.backend.activity.component.StoreEventCatalog;
import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Dziennik sklepu: każda zmiana przez API trafia do dziennika z osobą i opisem, odrzucona też, odczyty nie, a dziennik
 * widzi tylko {@code STORE_LOG:VIEW} w swoim sklepie.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StoreEventIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    StoreEventBuffer buffer;

    @Test
    void changesAndRefusalsLandInStoreLogForManagers() throws Exception {
        Cookie owner = login("wlasciciel", "haslo-wlasciciela");
        String storeId = createStore(owner);
        Cookie manager = employee("{\"STORE_LOG\":\"VIEW\"}", storeId, "Kierownik Zmiany");
        Cookie cashier = employee("{\"CASH_REPORT\":\"VIEW\"}", storeId, "Kasjer");

        mvc.perform(put("/api/stores/" + storeId + "/sidebar-order").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groups\":[]}"))
                .andExpect(status().is2xxSuccessful());
        // Kierownik z samym dziennikiem nie zmieni kolejności menu: odmowa też jest w dzienniku.
        mvc.perform(put("/api/stores/" + storeId + "/sidebar-order").cookie(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groups\":[]}"))
                .andExpect(status().isForbidden());
        // Odczyt nie jest zdarzeniem.
        mvc.perform(get("/api/stores/" + storeId + "/proto-settings").cookie(owner)).andExpect(status().isOk());
        buffer.flush(Duration.ofSeconds(10));

        String json = mvc.perform(get("/api/stores/" + storeId + "/events").cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> summaries = JsonPath.read(json, "$.items[*].summary");
        assertThat(summaries).containsExactly("Zmiana: kolejność menu", "Zmiana: kolejność menu");
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].kind")).containsExactly("FAILED", "ACTION");
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].userName"))
                .containsExactly("Kierownik Zmiany", "Właściciel");
        assertThat(JsonPath.<List<Integer>>read(json, "$.items[*].status")).containsExactly(403, 200);
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].moduleLabel"))
                .containsOnly("Ustawienia sklepu");

        mvc.perform(get("/api/stores/" + storeId + "/events").param("kind", "FAILED").cookie(manager))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/stores/" + storeId + "/events").param("query", "menu kolejność").cookie(manager))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/stores/" + storeId + "/events/filters").cookie(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[?(@.name == 'Kasjer')]").exists());

        // Kolejna porcja od ostatniego wpisu.
        String lastAt = JsonPath.read(json, "$.items[0].occurredAt");
        String lastId = JsonPath.read(json, "$.items[0].id");
        mvc.perform(get("/api/stores/" + storeId + "/events").param("beforeAt", lastAt).param("beforeId", lastId)
                        .cookie(manager))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("ACTION"));

        mvc.perform(get("/api/stores/" + storeId + "/events").cookie(cashier)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/events").cookie(manager)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/events").cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void catalogDescribesPathsInPolish() {
        assertThat(StoreEventCatalog.describeStore("POST", "/documents/pz/" + UUID.randomUUID() + "/gratis/restore"))
                .isEqualTo(new StoreEventCatalog.Description("documents", "Przywrócenie: gratis"));
        assertThat(StoreEventCatalog.describeStore("DELETE", "/cash/documents/" + UUID.randomUUID()))
                .isEqualTo(new StoreEventCatalog.Description("cash", "Usunięcie: dokument"));
        assertThat(StoreEventCatalog.describeStore("PUT", "/staff/schedule/entries"))
                .isEqualTo(new StoreEventCatalog.Description("schedule", "Zmiana: grafik"));
        assertThat(StoreEventCatalog.describeStore("POST", "/staff/time/requests/" + UUID.randomUUID() + "/approve"))
                .isEqualTo(new StoreEventCatalog.Description("time", "Zatwierdzenie: wniosek pracownika"));
        assertThat(StoreEventCatalog.describeStore("POST", "/imports/sales/uploads/" + UUID.randomUUID()))
                .isEqualTo(new StoreEventCatalog.Description("imports", "Dodanie: raport sprzedaży"));
        assertThat(StoreEventCatalog.describeCompany("PUT", "/api/settings/users/" + UUID.randomUUID()))
                .isEqualTo(new StoreEventCatalog.Description("company", "Zmiana: użytkownik"));
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep dziennik\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie employee(String permissions, String store, String name) throws Exception {
        Cookie owner = login("wlasciciel", "haslo-wlasciciela");
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "dz" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, ?, ?, ?::uuid) returning id""", String.class,
                username, name, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return login(username, "haslo-pracownika");
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
