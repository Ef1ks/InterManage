package com.intermanage.backend;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opis gazetki z centrali: pobranie z portalu w tle, wgranie, zastąpienie aktualizacją i pozycja w formularzu MEA po ITM 8 PROMO. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        // Klucz API MEA jest szyfrowany; w CI nie ma APP_SECRET_KEY (lokalnie bywa w backend/.env).
        "app.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MeaLeafletIntegrationTests {

    private static final List<String> HEADER = List.of("LP", "ROK", "TYDZIEŃ", "DATA POCZĄTKU", "DATA KOŃCA",
            "NR MEA", "ITM 8", "ITM 8 PROMO", "PRODUKT", "MARKA", "FORMA PROMOCJI", "RODZAJ MEGAPROMOCJI",
            "WARIANT MEGAPROMOCJI", "ILOŚĆ SZTUK W MEGAPROMOCJI", "CENA W MEGAPROMOCJI WIELOSZTUKI", "INNY GRATIS",
            "CENA CESJI DO ROZLICZEŃ", "CENA DLA KLIENTA", "Limit dla klienta", "FORMA DOSTAWY");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    MurphysoftClient client;

    @Test
    void documentLinesGetLeafletDescription() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner, String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(70_000, 79_999));
        String promoItm8 = "6" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        int year = ThreadLocalRandom.current().nextInt(2100, 2900);
        String document = jdbc.queryForObject("""
                insert into mea_documents (number, title, promo_from, promo_to, file_path, line_count, fetched_at,
                    attempts)
                values (?, 'MEA TEST', current_date + 30, current_date + 36, 'x.xls', 1, now(), 1) returning id""",
                String.class, number);
        jdbc.update("""
                insert into mea_document_lines (document_id, lp, mea_itm8, name, shelf_price, parallel_shelf_price)
                values (?::uuid, 1, ?, 'NUTELLA', 8.99, 11.49)""", document, promoItm8);

        upload(owner, storeId, year, number, promoItm8, "9,49", "LIMIT DZIENNY\nKLUCZOWY PRODUKT");
        // Aktualizacja tej samej gazetki zastępuje poprzednią.
        upload(owner, storeId, year, number, promoItm8, "8,99",
                "LIMIT DZIENNY\nRabat tylko dla klientów z KARTĄ MOJE IM / aplikacją");

        mvc.perform(get("/api/stores/" + storeId + "/mea/leaflets").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.year == " + year + ")]", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/stores/" + storeId + "/mea/documents/" + number).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaflets[0]").value("49/" + year))
                .andExpect(jsonPath("$.lines[0].leaflet.clientPrice").value(8.99))
                .andExpect(jsonPath("$.lines[0].leaflet.clientLimit").value(2))
                .andExpect(jsonPath("$.lines[0].leaflet.megaQuantity").value(2))
                .andExpect(jsonPath("$.lines[0].leaflet.megaPrice").value(17.98))
                .andExpect(jsonPath("$.lines[0].leaflet.section").value("PRODUKTY KLUCZOWE"))
                .andExpect(jsonPath("$.lines[0].leaflet.promoForm.length()").value(2))
                .andExpect(jsonPath("$.lines[0].leaflet.promoForm[1]")
                        .value("Rabat tylko dla klientów z KARTĄ MOJE IM / aplikacją"));
    }

    @Test
    void downloadsNewLeafletsFromPortalOnce() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner, String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        mvc.perform(put("/api/stores/" + storeId + "/mea/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"klucz\",\"apiSecret\":\"\"}"))
                .andExpect(status().isOk());
        int year = ThreadLocalRandom.current().nextInt(2100, 2900);
        String path = "../../data/cad/opis/" + year + ".xlsx";
        when(client.authorize(anyInt(), anyString()))
                .thenReturn(new MurphysoftClient.Token("token", Instant.now().plusSeconds(3600)));
        when(client.cadList(anyInt(), anyString())).thenReturn(cadList(path, "2026-11-14 09:28:00"));
        when(client.cadFile(anyInt(), anyString(), eq(path)))
                .thenReturn(leafletFile(year, "81495", "61662380", "8,99", "KLUCZOWY PRODUKT"));

        mvc.perform(post("/api/stores/" + storeId + "/mea/leaflets/sync").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloaded").value(1))
                .andExpect(jsonPath("$.leaflets[?(@.year == " + year + ")].fromPortal").value(true));
        // Ten sam plik (ścieżka i data zmiany) już jest.
        mvc.perform(post("/api/stores/" + storeId + "/mea/leaflets/sync").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloaded").value(0));
        // Aktualizacja w portalu (nowa data zmiany) zastępuje opis tej gazetki.
        when(client.cadList(anyInt(), anyString())).thenReturn(cadList(path, "2026-11-15 08:00:00"));
        mvc.perform(post("/api/stores/" + storeId + "/mea/leaflets/sync").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloaded").value(1))
                .andExpect(jsonPath("$.leaflets[?(@.year == " + year + ")]", org.hamcrest.Matchers.hasSize(1)));
    }

    private JsonNode cadList(String path, String updated) {
        return json.readTree("""
                {"top":[],"gazetki":[{"id":"7","name":"Gazetka 49/2026","position":"84","file":[{"id":3,
                "name":"Gazetka nr 49.xlsx","update_date":"%s","path":"%s"}]},
                {"id":"8","name":"Gazetka 51/2026","position":"86"}],"info":"ok"}""".formatted(updated, path));
    }

    @Test
    void rejectsOtherFile() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner, String.valueOf(ThreadLocalRandom.current().nextInt(12_000, 19_999)));
        byte[] file = new XlsxWriter().write("Arkusz", List.of(new XlsxWriter.Column("EAN", 10)),
                List.of(List.of("123")));
        mvc.perform(multipart("/api/stores/" + storeId + "/mea/leaflets")
                        .file(new MockMultipartFile("file", "inny.xlsx", "application/octet-stream", file))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static byte[] leafletFile(int year, String number, String promoItm8, String clientPrice,
            String promoForm) {
        List<XlsxWriter.Column> columns = HEADER.stream().map(header -> new XlsxWriter.Column(header, 12)).toList();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("1", "", "", "", "", "", "", "", "PRODUKTY KLUCZOWE", "", "", "", "", "", "", "", "",
                "", "", ""));
        rows.add(Arrays.asList("2", String.valueOf(year), "49", "06.12.2026", "06.12.2026", number, "681172",
                promoItm8, "G.SC.X.RM.M.NUTELLA 350G", "NUTELLA", promoForm, "X szt. za Y zł", "", "2", "17,98", "",
                "7,10", clientPrice, "2", "BAZA"));
        return new XlsxWriter().write("Arkusz", columns, rows);
    }

    private void upload(Cookie owner, String storeId, int year, String number, String promoItm8, String clientPrice,
            String promoForm) throws Exception {
        byte[] file = leafletFile(year, number, promoItm8, clientPrice, promoForm);
        mvc.perform(multipart("/api/stores/" + storeId + "/mea/leaflets")
                        .file(new MockMultipartFile("file", "Gazetka nr 49.xlsx", "application/octet-stream", file))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(49))
                .andExpect(jsonPath("$.lineCount").value(1));
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
