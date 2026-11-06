package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;

import jakarta.servlet.http.Cookie;

/** Raport zerówek: cadencier, stan 0, aktywność w kartotece i wybrana nomenklatura. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ZeroStockIntegrationTests {

    private static final String ZERO = "0000000000401";
    private static final String NO_STOCK = "0000000000402";
    private static final String OTHER_FAMILY = "0000000000403";
    private static final String OTHER_REGION = "0000000000404";
    private static final String ON_SHELF = "0000000000405";
    private static final String NEGATIVE = "0000000000406";
    private static final String INACTIVE = "0000000000407";
    private static final String NOT_IN_CADENCIER = "0000000000408";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String productImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', '2026-11-05 06:30', 1, 1, 0, 0, 0) returning id""",
                String.class, store);
        createNomenclature();
        String cadencier = jdbc.queryForObject("""
                insert into cadencier_imports (store_id, file_name, report_generated_at, total_rows)
                values (?::uuid, 'obecne w cad.xlsx', '2026-11-04 15:02', 7) returning id""", String.class, store);
        for (String itm8 : List.of(ZERO, NO_STOCK, OTHER_FAMILY, OTHER_REGION, ON_SHELF, NEGATIVE, INACTIVE)) {
            jdbc.update("insert into cadencier_items (store_id, itm8, ean, import_id) values (?::uuid, ?, null, ?::uuid)",
                    store, itm8, cadencier);
        }
        product(ZERO, "5900000000017", "BUDYN", "01 01 01", "0", true);
        product(NO_STOCK, "2404140000000", "ARBUZ", "01 01 02", null, true);
        product(OTHER_FAMILY, "5900000000024", "CHLEB", "01 02 01", "0", true);
        product(OTHER_REGION, "5900000000031", "SER", "02 01 01", "0", true);
        product(ON_SHELF, "5900000000048", "NA POLCE", "01 01 01", "3", true);
        product(NEGATIVE, "5900000000055", "UJEMNY", "01 01 01", "-2", true);
        product(INACTIVE, "5900000000062", "NIEAKTYWNY", "01 01 01", "0", false);
        product(NOT_IN_CADENCIER, "5900000000079", "POZA", "01 01 01", "0", true);
    }

    @Test
    void listsActiveCadencierArticlesWithoutStockByFamily() throws Exception {
        mvc.perform(get(url()).param("nomenclatures", "010101", "010102", "0102").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.selection[*].code", contains("010101", "010102", "0102")))
                .andExpect(jsonPath("$.groups[*].code", contains("0101", "0102")))
                .andExpect(jsonPath("$.groups[0].name").value("Pozycja 0101"))
                .andExpect(jsonPath("$.groups[0].items[*].name", contains("BUDYN", "ARBUZ")))
                .andExpect(jsonPath("$.groups[1].items[0].ean").value("5900000000024"));
    }

    @Test
    void regionCoversItsFamilies() throws Exception {
        mvc.perform(get(url()).param("nomenclatures", "01").cookie(owner))
                .andExpect(jsonPath("$.itemCount").value(3));
        mvc.perform(get(url()).param("nomenclatures", "02").cookie(owner))
                .andExpect(jsonPath("$.groups[0].items[*].itm8", contains(OTHER_REGION)));
    }

    @Test
    void withoutNomenclatureReportIsEmpty() throws Exception {
        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.groups", hasSize(0)));
    }

    @Test
    void printsPdfWithBarcodes() throws Exception {
        byte[] pdf = mvc.perform(get(url() + "/pdf").param("nomenclatures", "01").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void writesOrderFile() throws Exception {
        String file = mvc.perform(post(url() + "/order").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ean\":\"5900000000017\",\"quantity\":1},"
                                + "{\"ean\":\"2404140000000\",\"quantity\":12}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.US_ASCII);
        assertThat(file).isEqualTo("590000000001701\r\n240414000000012\r\n");
    }

    /** Kod ITM8 z wydruku skanowany do tabeli wprowadzania danych: krótki od 21-29 byłby kodem z etykiety wagi. */
    @Test
    void printedItm8FindsArticleInDataEntryTable() throws Exception {
        product("0000002984140", "5900000000086", "BISZKOPTY", "01 01 01", "0", true);
        mvc.perform(get("/api/stores/" + store + "/data-entry/products").param("ean", "0000002984140").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itm8").value("0000002984140"))
                .andExpect(jsonPath("$[0].catalogEan").value("5900000000086"))
                .andExpect(jsonPath("$[0].byItm8").value(true));
    }

    private String url() {
        return "/api/stores/" + store + "/reports/zero-stock";
    }

    private void product(String itm8, String ean, String name, String nomenclature, String stock, boolean active) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, stock, active, first_seen_import_id,
                    last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?::numeric, ?, ?::uuid, ?::uuid)""",
                store, itm8, ean, name, nomenclature, stock, active, productImport, productImport);
    }

    private void createNomenclature() {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 6, 6, 0, 0) returning id""", String.class, store);
        for (String code : List.of("01", "0101", "010101", "010102", "0102", "010201", "02", "0201", "020101")) {
            jdbc.update("""
                    insert into nomenclatures (store_id, code, level, parent_id, name, last_import_id)
                    values (?::uuid, ?, ?, (select id from nomenclatures where store_id = ?::uuid and code = ?), ?,
                        ?::uuid)""", store, code,
                    code.length() == 2 ? "REGION" : code.length() == 4 ? "FAMILY" : "SUBFAMILY", store,
                    code.substring(0, code.length() - 2), "Pozycja " + code, nomenclatureImport);
        }
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
