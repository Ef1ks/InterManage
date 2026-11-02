package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Arrays;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.component.XlsxWriter;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Karty produkcji: wgranie bazy z dawnego arkusza (schematy z wartości), formularz, karta z kopią parametrów,
 * wydruk, podgląd w sklepie zaprzyjaźnionym i przy ruchu międzymagazynowym, uprawnienia.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ProductionCardIntegrationTests {

    private static final String BAKED_SCHEMA = "Pieczenie 100°C · wewn. 75°C · schładzanie 20 do 4°C · 5 dni";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    XlsxWriter xlsxWriter;

    private Cookie owner;
    private String storeA;
    private String storeB;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        storeA = createStore();
        storeB = createStore();
    }

    @Test
    void importedBaseGivesCardsVisibleInPartnerStoreAndOnTransfer() throws Exception {
        mvc.perform(multipart(url(storeA, "/base/import")).file(baseFile()).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(4))
                .andExpect(jsonPath("$.schemasCreated").value(3));
        // Ponowne wgranie niczego nie dubluje.
        mvc.perform(multipart(url(storeA, "/base/import")).file(baseFile()).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.unchanged").value(4))
                .andExpect(jsonPath("$.schemasCreated").value(0));

        String base = mvc.perform(get(url(storeA, "/base")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles.length()").value(4))
                .andExpect(jsonPath("$.articles[0].name").value("PIEROGI RUSKIE LUZ"))
                .andExpect(jsonPath("$.articles[3].code").value("5423552"))
                .andExpect(jsonPath("$.schemas.length()").value(3))
                .andExpect(jsonPath("$.customStamp").value(false))
                .andReturn().getResponse().getContentAsString();
        String ruskie = JsonPath.read(base, "$.articles[0].id");
        String salad = JsonPath.read(base, "$.articles[2].id");
        String bakedSchema = JsonPath.<List<String>>read(base,
                "$.schemas[?(@.name == '" + BAKED_SCHEMA + "')].id").getFirst();
        mvc.perform(get(url(storeA, "/base")).cookie(owner))
                .andExpect(jsonPath("$.schemas[?(@.name == '" + BAKED_SCHEMA + "')].articleCount").value(2));

        mvc.perform(get(url(storeA, "/form/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // Schemat z artykułami nie daje się usunąć.
        mvc.perform(delete(url(storeA, "/schemas/" + bakedSchema)).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        String card = send(post(url(storeA, "/cards")), cardJson(ruskie, "4", salad, "2.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.lines[0].endHour").value(9))
                .andExpect(jsonPath("$.lines[0].bakingTemperature").value(100))
                .andExpect(jsonPath("$.lines[0].expiryDate").value("2026-10-27"))
                .andExpect(jsonPath("$.lines[1].bakingTemperature").doesNotExist())
                .andExpect(jsonPath("$.lines[1].expiryDate").value("2026-10-25"))
                .andReturn().getResponse().getContentAsString();
        String cardId = JsonPath.read(card, "$.id");

        // Zmiana schematu nie zmienia zapisanej karty, także po poprawieniu ilości.
        send(put(url(storeA, "/schemas/" + bakedSchema)),
                "{\"name\":\"Pieczenie\",\"bakingTemperature\":140,\"internalTemperature\":75,\"shelfLifeDays\":5}")
                .andExpect(status().isOk());
        send(put(url(storeA, "/cards/" + cardId)), cardJson(ruskie, "6", salad, "2.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(6))
                .andExpect(jsonPath("$.lines[0].bakingTemperature").value(100));

        mvc.perform(get(url(storeA, "/cards/" + cardId + "/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // Sklep, który nie jest zaprzyjaźniony, nie widzi karty.
        mvc.perform(get(url(storeB, "/cards/" + cardId)).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(get(url(storeB, "/cards?from=2026-10-01&to=2026-10-31&scope=PARTNERS")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));

        befriend(storeA, storeB);
        mvc.perform(get(url(storeB, "/cards?from=2026-10-01&to=2026-10-31&scope=PARTNERS")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lineCount").value(2))
                .andExpect(jsonPath("$[0].totalQuantity").value(8.5));
        mvc.perform(get(url(storeB, "/cards/" + cardId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
        mvc.perform(get(url(storeB, "/cards/" + cardId + "/pdf")).cookie(owner)).andExpect(status().isOk());
        send(put(url(storeB, "/cards/" + cardId)), cardJson(ruskie, "1", salad, "1"))
                .andExpect(status().isNotFound());

        // Karta przy ruchu międzymagazynowym: przypięcie w sklepie wysyłającym, wydruk w docelowym.
        String transfer = insertTransfer(storeA);
        String documentCards = "/api/stores/" + storeA + "/documents/mm/" + transfer + "/production-cards";
        mvc.perform(get(documentCards + "/candidates").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1));
        send(post(documentCards), "{\"cardId\":\"" + cardId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(documentCards + "/candidates").cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
        send(put("/api/stores/" + storeA + "/documents/mm/" + transfer + "/transfer-target"),
                "{\"storeId\":\"" + storeB + "\"}").andExpect(status().isOk());
        String incoming = mvc.perform(get("/api/stores/" + storeB + "/incoming-transfers").cookie(owner))
                .andReturn().getResponse().getContentAsString();
        String incomingCards = "/api/stores/" + storeB + "/incoming-transfers/"
                + JsonPath.read(incoming, "$.pending[0].id") + "/production-cards";
        mvc.perform(get(incomingCards).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(cardId));
        mvc.perform(get(incomingCards + "/" + cardId + "/pdf").cookie(owner)).andExpect(status().isOk());

        mvc.perform(delete(documentCards + "/" + cardId).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(incomingCards + "/" + cardId + "/pdf").cookie(owner)).andExpect(status().isNotFound());

        // Usunięcie to znacznik: karta znika z listy i wraca po przywróceniu.
        mvc.perform(delete(url(storeA, "/cards/" + cardId)).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(url(storeA, "/cards?from=2026-10-01&to=2026-10-31")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
        send(post(url(storeA, "/cards/" + cardId + "/restore")), "{}").andExpect(status().isOk());
        mvc.perform(get(url(storeA, "/cards?from=2026-10-01&to=2026-10-31")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void viewPermissionCannotChangeCardsAndBadFileIsRejected() throws Exception {
        mvc.perform(multipart(url(storeA, "/base/import")).file(baseFile()).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        String base = mvc.perform(get(url(storeA, "/base")).cookie(owner)).andReturn().getResponse()
                .getContentAsString();
        String ruskie = JsonPath.read(base, "$.articles[0].id");
        String salad = JsonPath.read(base, "$.articles[2].id");

        Cookie viewer = employee("{\"DELI_PRODUCTION\":\"VIEW\"}", storeA);
        mvc.perform(get(url(storeA, "/base")).cookie(viewer)).andExpect(status().isOk());
        mvc.perform(post(url(storeA, "/cards")).cookie(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(cardJson(ruskie, "1", salad, "1")))
                .andExpect(status().isForbidden());
        mvc.perform(get(url(storeB, "/base")).cookie(viewer)).andExpect(status().isForbidden());

        byte[] noHeader = xlsxWriter.write("Baza", List.of(new XlsxWriter.Column("Kod", 10)),
                List.of(List.of("123")));
        mvc.perform(multipart(url(storeA, "/base/import"))
                        .file(new MockMultipartFile("file", "baza.xlsx", "application/octet-stream", noHeader))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** Fragment arkusza "Baza" z pliku Google, z tymi samymi kolumnami. */
    private MockMultipartFile baseFile() {
        List<XlsxWriter.Column> columns = List.of("Ean", "Nazwa", "Nomenklatura", "Temperatura pieczenia",
                "Czas schladzania", "Temperatura schladzania", "Czy pieczone", "Czas", "Wewnętrzna").stream()
                .map(header -> new XlsxWriter.Column(header, 10)).toList();
        List<List<Object>> rows = List.of(
                Arrays.asList("2416090000000", "PIEROGI RUSKIE LUZ", "36 60 01", 100, 20, 4, 1, 5, 75),
                Arrays.asList("2416100000000", "PIEROGI Z MIESEM LUZ", "36 60 01", 100, 20, 4, 1, 5, 75),
                Arrays.asList(null, "SALATKA BENIAMIN", "36 60 04", null, null, null, null, 3, null),
                Arrays.asList("5423552", "SZPINAK SIEKANY W KULK 2.5KG", "36 60 06", null, null, null, 0, 5, null));
        return new MockMultipartFile("file", "Zeszyt1.xlsx", "application/octet-stream",
                xlsxWriter.write("Baza", columns, rows));
    }

    private static String cardJson(String first, String firstQuantity, String second, String secondQuantity) {
        return """
                {"productionDate":"2026-10-22","lines":[
                  {"articleId":"%s","quantity":%s,"startHour":8},
                  {"articleId":"%s","quantity":%s,"startHour":10}]}""".formatted(first, firstQuantity, second,
                secondQuantity);
    }

    private void befriend(String inviting, String invited) throws Exception {
        String json = send(post("/api/stores/" + inviting + "/partners"), "{\"storeId\":\"" + invited + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String partnerId = JsonPath.read(json, "$.partners[0].id");
        send(post("/api/stores/" + invited + "/partners/" + partnerId + "/accept"), "{}")
                .andExpect(status().isOk());
    }

    /** Ruch międzymagazynowy z dnia karty, tak jakby przyszedł z raportu Wejścia/Wyjścia. */
    private String insertTransfer(String storeId) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'MM', 'MM0000120261022', ?, 1, -20, 30, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, LocalDateTime.of(2026, 10, 22, 12, 0), importId, importId);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String url(String storeId, String path) {
        return "/api/stores/" + storeId + "/production" + path;
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** Pracownik z rolą o podanych uprawnieniach, przypisany do sklepu. */
    private Cookie employee(String permissions, String store) throws Exception {
        String roleJson = send(post("/api/settings/roles"),
                "{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":" + permissions + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "kp" + UUID.randomUUID().toString().substring(0, 8);
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
