package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Lista artykułów: filtry, grupy nomenklatury, wydruk i Excel. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ArticleListIntegrationTests {

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
        String older = productImport("2026-11-04 06:30");
        productImport = productImport("2026-11-05 06:30");
        createNomenclature();
        product("0000000000501", "BUDYN", "DR OETKER", "01 01 01", "5", "1.00", "2.99", productImport);
        product("0000000000502", "CHLEB", "PIEKARNIA", "01 02 01", "0", "2.00", "3.49", productImport);
        product("0000000000503", "SER", "MLEKOVITA", "02 01 01", "-1", "10.00", "15.99", productImport);
        product("0000000000504", "STARY", "DR OETKER", "01 01 01", "2", "1.00", "2.99", older);
    }

    @Test
    void groupsByFamilyAndFilters() throws Exception {
        mvc.perform(get(url()).param("groupBy", "FAMILY").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.groups[*].code", contains("01", "0101", "0102", "02", "0201")))
                .andExpect(jsonPath("$.groups[0].count").value(2))
                .andExpect(jsonPath("$.items[*].name", contains("BUDYN", "CHLEB", "SER")))
                .andExpect(jsonPath("$.items[0].marginPercent").value(47.86))
                .andExpect(jsonPath("$.items[0].stockValue").value(5.00));

        mvc.perform(get(url()).param("brands", "dr oetker").param("allArticles", "true").cookie(owner))
                .andExpect(jsonPath("$.items[*].name", contains("BUDYN", "STARY")));
        mvc.perform(get(url()).param("stock", "NEGATIVE").cookie(owner))
                .andExpect(jsonPath("$.items[*].name", contains("SER")));
        mvc.perform(get(url()).param("nomenclatures", "01").param("sort", "SALE_PRICE").param("direction", "desc")
                        .cookie(owner))
                .andExpect(jsonPath("$.items[*].name", contains("CHLEB", "BUDYN")));
    }

    @Test
    void printsPdfAndExcel() throws Exception {
        byte[] pdf = mvc.perform(get(url() + "/pdf").param("groupBy", "SUBFAMILY")
                        .param("columns", "ITM8", "NAME", "SUBFAMILY", "STOCK", "MARGIN").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
        mvc.perform(get(url() + "/pdf").param("groupBy", "REGION").param("articles", "false").cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(url() + "/xlsx").param("groupBy", "REGION").cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(url() + "/pdf").param("columns", "NIE_MA").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    private String url() {
        return "/api/stores/" + store + "/reports/article-list";
    }

    private String productImport(String generatedAt) {
        return jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', ?::timestamp, 1, 1, 0, 0, 0) returning id""",
                String.class, store, generatedAt);
    }

    private void product(String itm8, String name, String brand, String nomenclature, String stock, String purchase,
            String sale, String lastImport) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, nomenclature, stock, purchase_price,
                    sale_price, vat_code, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, 1, true, ?::uuid, ?::uuid)""",
                store, itm8, "59" + itm8.substring(2), name, brand, nomenclature, stock, purchase, sale, lastImport,
                lastImport);
    }

    private void createNomenclature() {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 6, 6, 0, 0) returning id""", String.class, store);
        for (String code : List.of("01", "0101", "010101", "0102", "010201", "02", "0201", "020101")) {
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
