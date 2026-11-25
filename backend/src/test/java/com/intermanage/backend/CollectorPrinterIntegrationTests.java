package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Drukarki Bluetooth sklepu: lista w Intermanage i jej wersja na kolektorze. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class CollectorPrinterIntegrationTests {

    @Autowired
    MockMvc mvc;

    private Cookie owner;
    private String storeId;

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        storeId = JsonPath.read(mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep drukarek\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void collectorGetsPrintersOfItsStoreWhenListChanges() throws Exception {
        String key = newCollector();
        String empty = statusOf(key).get("printers");
        assertThat(empty).matches("[0-9a-f]{32}");
        assertThat(printers(key)).containsEntry("count", "0").containsEntry("printers", empty);

        add("{\"name\":\"Garmaż 1\",\"mac\":\"ac:3f:a4-12 34:56\",\"model\":\"QLn220\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mac").value("AC3FA4123456"));
        add("{\"name\":\"Magazyn\",\"mac\":\"AC3FA4123456\"}").andExpect(status().isConflict());
        add("{\"name\":\"Zły\",\"mac\":\"AC3FA41234\"}").andExpect(status().isBadRequest());
        add("{\"name\":\"Zły PIN\",\"mac\":\"AC3FA4123457\",\"pin\":\"12 34\"}").andExpect(status().isBadRequest());
        String body = add("{\"name\":\"Alejka\",\"mac\":\"AC3FA4123457\",\"pin\":\"0000\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Po nazwie.
        assertThat((String) JsonPath.read(body, "$[0].name")).isEqualTo("Alejka");
        String alley = JsonPath.read(body, "$[0].id");

        Map<String, String> list = printers(key);
        String version = list.get("printers");
        assertThat(version).isNotEqualTo(empty).isEqualTo(statusOf(key).get("printers"));
        assertThat(list).containsEntry("count", "2")
                .containsEntry("printer.1.name", "Alejka").containsEntry("printer.1.pin", "0000")
                .containsEntry("printer.2.name", "Garmaż 1").containsEntry("printer.2.mac", "AC3FA4123456")
                .containsEntry("printer.2.model", "QLn220").containsEntry("printer.2.pin", "");

        mvc.perform(put(url("/" + alley)).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alejka 2\",\"mac\":\"AC3FA4123457\"}")).andExpect(status().isOk());
        assertThat(statusOf(key).get("printers")).isNotEqualTo(version);
        assertThat(printers(key)).containsEntry("printer.1.name", "Alejka 2").containsEntry("printer.1.pin", "");

        // Usunięta znika z kolektora i wraca po przywróceniu.
        mvc.perform(delete(url("/" + alley)).cookie(owner).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].deletedAt").isNotEmpty());
        assertThat(printers(key)).containsEntry("count", "1").containsEntry("printer.1.name", "Garmaż 1");
        mvc.perform(post(url("/" + alley + "/restore")).cookie(owner).with(csrf())).andExpect(status().isOk());
        assertThat(printers(key)).containsEntry("count", "2");

        mvc.perform(get("/api/collector/printers")).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions add(String json) throws Exception {
        return mvc.perform(post(url("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String newCollector() throws Exception {
        String code = JsonPath.read(mvc.perform(post("/api/stores/" + storeId + "/collectors").cookie(owner)
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kolektor\"}"))
                .andReturn().getResponse().getContentAsString(), "$[0].pairCode");
        return pairs(mvc.perform(post("/api/collector/pair").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("code=" + code + "&device=MC3190&version=1.5.0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get("key");
    }

    private Map<String, String> statusOf(String key) throws Exception {
        return collectorGet("/api/collector/status", key);
    }

    private Map<String, String> printers(String key) throws Exception {
        return collectorGet("/api/collector/printers", key);
    }

    private Map<String, String> collectorGet(String path, String key) throws Exception {
        return pairs(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private static Map<String, String> pairs(byte[] body) {
        Map<String, String> result = new HashMap<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
            int at = line.indexOf('=');
            result.put(line.substring(0, at), line.substring(at + 1));
        }
        return result;
    }

    private String url(String rest) {
        return "/api/stores/" + storeId + "/printers" + rest;
    }
}
