package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Zwrot do dostawcy z listy kolektora (dostawca z ostatnich dostaw, korekta podpięta automatycznie po artykułach) oraz
 * inwentaryzacja i zamówienie z kolektora w historii (na później, plik z historii, wgranie).
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class SupplierReturnIntegrationTests {

    private static final String BEER = "0000001234567";
    private static final String BUTTER = "0000006893790";
    private static final String BEER_EAN = "5903538901205";
    private static final String BUTTER_EAN = "5900000000001";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String storeId;
    private String importId;
    private String listId;

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        storeId = JsonPath.read(mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep zwrotów\",\"active\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String report = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', now(), 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        product(BEER, BEER_EAN, "PIWO LOMZA 4X0,5L", report);
        product(BUTTER, BUTTER_EAN, "MASLO EXTRA 200G", report);
        jdbc.update("insert into suppliers (store_id, code, keyword, name) values (?::uuid, '50', 'LOMZA', 'BROWAR')",
                storeId);
        importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 3, 3, 3, 0, 0) returning id""", String.class, storeId);

        String key = newCollector();
        listId = UUID.randomUUID().toString();
        String lines = BEER + "\t" + BEER_EAN + "\t" + BEER_EAN + "\tPIWO LOMZA 4X0,5L\tLOMZA\t3\t3\t1\n"
                + BUTTER + "\t" + BUTTER_EAN + "\t" + BUTTER_EAN + "\tMASLO EXTRA 200G\t\t1.5\t1.5\t2\n";
        mvc.perform(post("/api/collector/lists").header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("id=" + listId + "&started=20261125081500&lines="
                                + URLEncoder.encode(lines, StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
    }

    @Test
    void returnFromCollectorFindsSupplierAndAttachesCorrectionByArticles() throws Exception {
        LocalDate today = LocalDate.now();
        String delivery = document("PZ", "999300", today.minusDays(10), "120.00");
        line(delivery, 1, BEER, BEER_EAN, "10", "100.00");
        line(delivery, 2, BUTTER, BUTTER_EAN, "4", "20.00");
        String correction = document("WZ", "WZ0001", today, "-30.00");
        line(correction, 1, BEER, BEER_EAN, "-3", "-30.00");
        String other = document("WZ", "WZ0002", today, "-5.00");
        line(other, 1, "0000000000999", "5900000000999", "-1", "-5.00");

        String base = "/api/stores/" + storeId + "/supplier-returns";
        String created = mvc.perform(post(base + "/from-collector").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"listIds\":[\"" + listId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.supplier.code").value("50"))
                .andExpect(jsonPath("$.summary.lineCount").value(2))
                // Korekta z jednym z dwóch artykułów (połowa) i jedyna taka: podpięta sama, zwrot rozliczony.
                .andExpect(jsonPath("$.summary.correction.number").value("WZ0001"))
                .andExpect(jsonPath("$.summary.status").value("CORRECTED"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.summary.id");
        assertThat(JsonPath.<java.util.List<Boolean>>read(created, "$.events[*].automatic")).contains(true);
        mvc.perform(get("/api/stores/" + storeId + "/collector-lists").cookie(owner))
                .andExpect(jsonPath("$[0].protocols[0].kind").value("SUPPLIER_RETURN"));

        mvc.perform(get(base + "/" + id + "/candidates").cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].document.bonNumber").value("9993"))
                .andExpect(jsonPath("$.deliveries[0].common").value(2));
        mvc.perform(put(base + "/" + id + "/delivery").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + delivery + "\"}"))
                .andExpect(jsonPath("$.summary.delivery.bonNumber").value("9993"));
        // Bon korekty przy jednym zwrocie: drugi zwrot go nie dostanie.
        String second = JsonPath.read(mvc.perform(post(base + "/from-protocols").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itm8\":\"" + BEER + "\",\"ean\":\"" + BEER_EAN
                                + "\",\"name\":\"PIWO\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.correction").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.summary.id");
        mvc.perform(put(base + "/" + second + "/correction").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + correction + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put(base + "/" + second + "/status").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"comment\":\"Dostawca nie przyjął\"}"))
                .andExpect(jsonPath("$.summary.status").value("REJECTED"))
                .andExpect(jsonPath("$.events[-1].detail").value("Do zgłoszenia → Odrzucony: Dostawca nie przyjął"));
        mvc.perform(get(base + "/count").cookie(owner)).andExpect(jsonPath("$.open").value(0));
        byte[] pdf = mvc.perform(get(base + "/" + id + "/pdf").cookie(owner)).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(pdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        save("zwrot.pdf", pdf);
    }

    /** {@code -DargLine=-Dcollector.pdf.out=<folder>} zapisuje wydruki do obejrzenia. */
    private static void save(String name, byte[] content) throws java.io.IOException {
        String folder = System.getProperty("collector.pdf.out");
        if (folder != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(folder, name), content);
        }
    }

    @Test
    void inventoryForLaterGetsFileFromHistoryAndOrderKeepsCartons() throws Exception {
        String base = "/api/stores/" + storeId + "/collector-files";
        String created = mvc.perform(post(base).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"INVENTORY\",\"listIds\":[\"" + listId + "\"],\"note\":\"Na 1 grudnia\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.file.status").value("WAITING"))
                .andExpect(jsonPath("$.file.lineCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.file.id");
        mvc.perform(get(base + "/count?kind=INVENTORY").cookie(owner)).andExpect(jsonPath("$.waiting").value(1));

        MockHttpServletResponse file = mvc.perform(get(base + "/" + id + "/file").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(file.getContentAsString()).isEqualTo(BEER_EAN + "00003000\r\n" + BUTTER_EAN + "00001500\r\n");
        assertThat(file.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("INVENTAIRE_").contains(".Traite0");
        mvc.perform(post(base + "/" + id + "/loaded").cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"INVENTAIRE_x.Traite0\"}"))
                .andExpect(jsonPath("$.status").value("LOADED"));
        mvc.perform(get(base + "/count?kind=INVENTORY").cookie(owner)).andExpect(jsonPath("$.waiting").value(0));

        String order = JsonPath.read(mvc.perform(post(base).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ORDER\",\"listIds\":[\"" + listId + "\"],\"lines\":[{\"ean\":\""
                                + BEER_EAN + "\",\"quantity\":4}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.file.id");
        assertThat(mvc.perform(get(base + "/" + order + "/file").cookie(owner)).andReturn().getResponse()
                .getContentAsString()).isEqualTo(BEER_EAN + "04\r\n");
        mvc.perform(get(base).cookie(owner)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("ORDER"));

        // Z tabeli wprowadzania danych albo Protokołów: bez list, ilości z pozycji; kod, który nie jest EAN-em, odpada.
        String fromTable = JsonPath.read(mvc.perform(post(base).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"INVENTORY\",\"items\":[{\"itm8\":\"" + BUTTER + "\",\"ean\":\""
                                + BUTTER_EAN + "\",\"name\":\"MASLO\",\"quantity\":7},"
                                + "{\"ean\":\"ABC\",\"name\":\"ZLY\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.skipped").value(1))
                .andReturn().getResponse().getContentAsString(), "$.file.id");
        assertThat(mvc.perform(get(base + "/" + fromTable + "/file").cookie(owner)).andReturn().getResponse()
                .getContentAsString()).isEqualTo(BUTTER_EAN + "00007000\r\n");
        mvc.perform(post(base).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ORDER\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        assertThat(mvc.perform(get(base + "/" + id + "/xlsx").cookie(owner)).andReturn().getResponse()
                .getContentAsByteArray()).startsWith("PK".getBytes(StandardCharsets.US_ASCII));
        save("inwentaryzacja.pdf", mvc.perform(get(base + "/" + id + "/pdf").cookie(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String newCollector() throws Exception {
        String code = JsonPath.read(mvc.perform(post("/api/stores/" + storeId + "/collectors").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kolektor\"}"))
                .andReturn().getResponse().getContentAsString(), "$[0].pairCode");
        String response = mvc.perform(post("/api/collector/pair").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("code=" + code + "&device=MC3190&version=1.0.0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String line : response.split("\n")) {
            if (line.startsWith("key=")) {
                return line.substring(4);
            }
        }
        throw new IllegalStateException("Brak klucza kolektora");
    }

    private void product(String itm8, String ean, String name, String importId) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, unit, net_content, purchase_price, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, 'szt.', 1, 10, 16.49, true, ?::uuid, ?::uuid)""",
                storeId, itm8, ean, name, importId, importId);
    }

    private String document(String type, String number, LocalDate day, String value) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, supplier_code, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, 1, ?::numeric, 0, '50', ?::uuid, ?::uuid) returning id::text""",
                String.class, storeId, type, number, day.toString(), value, importId, importId);
    }

    private void line(String documentId, int number, String itm8, String ean, String quantity, String value) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, name, quantity, purchase_value,
                    supplier_code)
                values (?::uuid, ?, ?, ?, 'ARTYKUŁ', ?::numeric, ?::numeric, '50')""", UUID.fromString(documentId),
                number, itm8, ean, quantity, value);
    }
}
