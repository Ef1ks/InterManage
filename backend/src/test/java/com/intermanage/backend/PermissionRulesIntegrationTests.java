package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultMatcher;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Zasady uprawnień z {@code Access}: słowniki widzi każdy z dostępem do sklepu, kartę artykułu Sprzedaż, Magazyn
 * i Wprowadzanie danych, a zmiana słownika dalej wymaga EDIT swojego narzędzia.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PermissionRulesIntegrationTests {

    private static final ResultMatcher NOT_FORBIDDEN =
            result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeId = createStore();
    }

    @Test
    void dictionariesForEveryoneInStore() throws Exception {
        // Pracownik z samym grafikiem (żadne narzędzie towarowe) korzysta ze słowników sklepu.
        Cookie scheduler = employee("{\"STAFF_SCHEDULE\":\"VIEW\"}", storeId);
        for (String path : new String[] {"/suppliers", "/reports/nomenclatures", "/partners", "/products?query=mleko",
                "/price-rounding"}) {
            mvc.perform(get(store(path)).cookie(scheduler)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/gus/companies?nip=5260250274").cookie(scheduler)).andExpect(NOT_FORBIDDEN);

        // Słownik tylko w swoim sklepie, a zmiana słownika wymaga EDIT narzędzia.
        mvc.perform(get("/api/stores/" + createStore() + "/suppliers").cookie(scheduler))
                .andExpect(status().isForbidden());
        mvc.perform(post(store("/suppliers")).cookie(scheduler).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nowy dostawca\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void articleCardForReceivingGoods() throws Exception {
        Cookie receiving = employee("{\"DATA_ENTRY\":\"VIEW\"}", storeId);
        mvc.perform(get(store("/reports/articles/0000001234567")).cookie(receiving)).andExpect(NOT_FORBIDDEN);
        mvc.perform(get(store("/reports/articles/0000001234567/deliveries")).cookie(receiving))
                .andExpect(status().isOk());
        mvc.perform(get(store("/products/0000001234567/prices")).cookie(receiving)).andExpect(NOT_FORBIDDEN);
        mvc.perform(get(store("/reports/sales-history?from=2026-10-01&to=2026-10-31&itm8=0000001234567"))
                .cookie(receiving)).andExpect(status().isOk());
        // Wykres całego sklepu to już raport sprzedaży.
        mvc.perform(get(store("/reports/sales-history?from=2026-10-01&to=2026-10-31")).cookie(receiving))
                .andExpect(status().isForbidden());

        Cookie scheduler = employee("{\"STAFF_SCHEDULE\":\"VIEW\"}", storeId);
        mvc.perform(get(store("/reports/articles/0000001234567/deliveries")).cookie(scheduler))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullDashboardShowsStoreResultsOnly() throws Exception {
        Cookie dashboard = employee("{\"DASHBOARD\":\"VIEW\"}", storeId);
        mvc.perform(get(store("/reports")).cookie(dashboard)).andExpect(status().isOk());
        mvc.perform(get(store("/reports/sales-history?from=2026-10-01&to=2026-10-31")).cookie(dashboard))
                .andExpect(status().isOk());
        // Bez raportów sprzedaży: artykuł, nomenklatura i porównanie dalej wymagają SALES.
        for (String path : new String[] {"/reports/sales-history?from=2026-10-01&to=2026-10-31&itm8=0000001234567",
                "/reports/sales-history?from=2026-10-01&to=2026-10-31&parent=01",
                "/reports/sales-history?from=2026-10-01&to=2026-10-31&unassigned=true",
                "/reports/sales-history?from=2026-10-01&to=2026-10-31&nomenclatures=01",
                "/reports/articles/0000001234567"}) {
            mvc.perform(get(store(path)).cookie(dashboard)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/stores/" + createStore() + "/reports").cookie(dashboard))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingNeverNeedsEdit() throws Exception {
        // Zamówienia garmażu: podgląd widzi zaproszenia i wyszukuje w kartotece, zaprasza dopiero EDIT.
        Cookie deli = employee("{\"DELI_ORDERS\":\"VIEW\"}", storeId);
        mvc.perform(get(store("/deli-order/invitations")).cookie(deli)).andExpect(status().isOk());
        mvc.perform(get(store("/deli-order/products?query=schab")).cookie(deli)).andExpect(status().isOk());
        mvc.perform(post(store("/deli-order/invitations")).cookie(deli).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[\"" + createStore() + "\"]}"))
                .andExpect(status().isForbidden());
    }

    private String store(String path) {
        return "/api/stores/" + storeId + path;
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

    private Cookie employee(String permissions, String store) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "pr" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, store);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
