package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zamówienie MEA do ATHOS: ZIP R… z plikiem V…, potwierdzenie zapisu i blokada po terminie rezerwacji. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MeaOrderIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void savesOrderFileAndMarksItAfterSave() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999));
        String storeId = createStore(owner, storeNumber);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(70_000, 79_999));
        String document = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, reservation_from, reservation_to,
                    dispatch_1, file_path, line_count, fetched_at, attempts)
                values (?, 'MEA TEST', current_date + 30, current_date + 36, current_date - 2, current_date + 2,
                    current_date + 27, 'x.xls', 2, now(), 1) returning id""", String.class, number);
        jdbc.update("""
                insert into mea_document_lines (document_id, lp, mea_itm8, name) values
                    (?::uuid, 1, '61665965', 'SER'), (?::uuid, 2, '61665966', 'CIASTO')""", document, document);

        MockHttpServletResponse response = mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number
                        + "/athos-order").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[3]}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "R" + number + storeNumber.substring(0, 2) + "." + storeNumber.substring(2))))
                .andReturn().getResponse();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(zip.getNextEntry().getName())
                    .isEqualTo("V" + number + storeNumber.substring(0, 2) + "." + storeNumber.substring(2));
            assertThat(new String(zip.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo(
                    number + "61665965" + "0".repeat(32) + number + "61665966" + "0003" + "0".repeat(28));
        }
        String orderId = response.getHeader("X-Mea-Order-Id");

        // Bez potwierdzenia zapisu zamówienia nie ma w dokumencie.
        mvc.perform(get("/api/stores/" + storeId + "/mea/documents/" + number).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
        mvc.perform(post("/api/stores/" + storeId + "/mea/athos-orders/" + orderId + "/saved").cookie(owner)
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/mea/documents/" + number).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].positions").value(1))
                .andExpect(jsonPath("$.orders[0].colis").value(3));

        // Rozrzut bez daty i zamknięta rezerwacja.
        mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number + "/athos-order").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[0,2]}]}"))
                .andExpect(status().isBadRequest());
        jdbc.update("update mea_documents set reservation_to = current_date - 1 where id = ?::uuid", document);
        mvc.perform(post("/api/stores/" + storeId + "/mea/documents/" + number + "/athos-order").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"61665966\",\"colis\":[1]}]}"))
                .andExpect(status().isConflict());
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep MEA " + number + "\",\"active\":true}"))
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
