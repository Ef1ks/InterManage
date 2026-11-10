package com.intermanage.backend;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.gus.component.GusClient;
import com.intermanage.backend.gus.dto.GusCompany;
import com.intermanage.backend.gus.service.GusService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Makro zakładania dostawcy w Mercalys: dane z GUS przycięte do pól, wolny kod, rezerwacja i jej cofnięcie. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MercalysSupplierIntegrationTests {

    private static final String NEW_NIP = "7812022538";
    private static final String UNKNOWN_NIP = "7821977018";
    private static final String KNOWN_NIP = "9591452482";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    GusClient gusClient;

    @MockitoBean
    GusService gus;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeId = createStore();
        when(gusClient.configured()).thenReturn(true);
        when(gus.find(anyString())).thenReturn(Optional.empty());
        when(gus.find(NEW_NIP)).thenReturn(Optional.of(new GusCompany(NEW_NIP, "123456789",
                "Piekarnia Zbożowa SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ Oddział w Zielonej Górze",
                "ul. Długa 5/2", "65-001", "Zielona Góra", "ul. Długa 5/2, 65-001 Zielona Góra", null)));
        // Kody 1, 2, 3 i 5: pierwszy wolny to 4.
        for (String code : new String[] { "1", "2", "5" }) {
            jdbc.update("insert into suppliers (store_id, code, keyword, name) values (?::uuid, ?, ?, ?)", storeId,
                    code, "D" + code, "DOSTAWCA " + code);
        }
        jdbc.update("insert into suppliers (store_id, code, keyword, name, nip) values (?::uuid, '3', 'KOLP', "
                + "'KOLPORTER', ?)", storeId, KNOWN_NIP);
    }

    @Test
    void draftsReservesAndReleasesCode() throws Exception {
        String base = "/api/stores/" + storeId + "/suppliers";
        mvc.perform(get(base + "/mercalys-draft").param("nip", KNOWN_NIP).cookie(owner))
                .andExpect(status().isConflict());

        mvc.perform(get(base + "/mercalys-draft").param("nip", NEW_NIP).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("4"))
                .andExpect(jsonPath("$.fromGus").value(true))
                .andExpect(jsonPath("$.name1").value("PIEKARNIA ZBOZOWA SP. Z O.O."))
                .andExpect(jsonPath("$.name2").value("ODDZIAL W ZIELONEJ GORZE"))
                .andExpect(jsonPath("$.keyword").value("PIEKARNI"))
                .andExpect(jsonPath("$.street").value("UL. DLUGA 5/2"))
                .andExpect(jsonPath("$.postalCode").value("65001"))
                .andExpect(jsonPath("$.city").value("ZIELONA GORA"))
                .andExpect(jsonPath("$.previous").doesNotExist());

        String created = mvc.perform(post(base + "/mercalys-creations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nip\":\"" + NEW_NIP + "\",\"code\":\"4\",\"name\":\"PIEKARNIA ZBOZOWA\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("4"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        // Kod 4 zarezerwowany, 5 zajęty: następny to 6, a ponowne zakładanie NIP-u jest ostrzegane.
        mvc.perform(get(base + "/mercalys-draft").param("nip", NEW_NIP).cookie(owner))
                .andExpect(jsonPath("$.code").value("6"))
                .andExpect(jsonPath("$.previous.code").value("4"));
        mvc.perform(post(base + "/mercalys-creations").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nip\":\"" + NEW_NIP + "\",\"code\":\"4\",\"name\":\"PIEKARNIA ZBOZOWA\"}"))
                .andExpect(status().isConflict());

        mvc.perform(delete(base + "/mercalys-creations/" + id).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(base + "/mercalys-draft").param("nip", NEW_NIP).cookie(owner))
                .andExpect(jsonPath("$.code").value("4"))
                .andExpect(jsonPath("$.previous").doesNotExist());
    }

    @Test
    void withoutGusTakesNameFromInvoice() throws Exception {
        mvc.perform(get("/api/stores/" + storeId + "/suppliers/mercalys-draft").param("nip", UNKNOWN_NIP)
                        .param("name", "Firma Testowa Łódź").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromGus").value(false))
                .andExpect(jsonPath("$.gusProblem").value("GUS nie ma firmy o tym NIP-ie"))
                .andExpect(jsonPath("$.name1").value("FIRMA TESTOWA LODZ"))
                .andExpect(jsonPath("$.street").value(""));
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
