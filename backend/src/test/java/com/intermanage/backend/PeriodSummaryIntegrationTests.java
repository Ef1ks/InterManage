package com.intermanage.backend;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

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

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PeriodSummaryIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void summarisesSalesAndLossesByRegionGroupsWithBudgets() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        String apple = insertProduct(storeId, productImport, "JABŁKO", "14 01 01");
        String bread = insertProduct(storeId, productImport, "PIECZYWO WASA", "01 01 01");
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 2, 2, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into nomenclatures (store_id, code, level, name, last_import_id)
                values (?::uuid, '14', 'REGION', '14_OWOCE/WARZYWA/ROSLINY', ?::uuid)""", storeId,
                nomenclatureImport);

        String salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-09-28 07:00:00', date '2026-09-21',
                    date '2026-09-27', 3, 3, 0, 0) returning id""", String.class, storeId);
        // Owoce: netto 100, brutto 105, marża 20. Delikatesy: netto 300, brutto 324, marża 60. Poza okresem: pomijane.
        insertSale(storeId, salesImport, "2026-09-21", apple, "80", "105", "5", "20");
        insertSale(storeId, salesImport, "2026-09-22", bread, "240", "324", "24", "60");
        insertSale(storeId, salesImport, "2026-09-20", bread, "1000", "2000", "100", "900");

        String documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', timestamp '2026-09-28 08:00:00', date '2026-09-21',
                    date '2026-09-27', 3, 3, 3, 0, 0) returning id""", String.class, storeId);
        // Likwidacja owoców 10,50 zł (10% sprzedaży brutto), RW (nie liczy się) 3 zł, likwidacja bez nomenklatury 2 zł.
        insertLine(storeId, documentImport, "PL", "PL01", "140101", "-10.5");
        insertLine(storeId, documentImport, "RWPO", "RW01", "010203", "-3");
        insertLine(storeId, documentImport, "PL", "PL02", null, "-2");

        mvc.perform(put(reportsUrl(storeId) + "/region-budgets").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"budgets\":[{\"regionCode\":\"14\",\"marginPercent\":20.4,"
                                + "\"maxLossPercent\":4},{\"regionCode\":\"01\",\"marginPercent\":23}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.regionCode == '14')].marginPercent").value(20.4))
                .andExpect(jsonPath("$[?(@.regionCode == '14')].name").value("OWOCE/WARZYWA/ROSLINY"))
                .andExpect(jsonPath("$[?(@.regionCode == '14')].group").value("Świeże tradycyjne"))
                .andExpect(jsonPath("$[?(@.regionCode == '01')].maxLossPercent").value(org.hamcrest.Matchers.contains(nullValue())));
        mvc.perform(put(reportsUrl(storeId) + "/region-budgets").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"budgets\":[{\"regionCode\":\"14\",\"maxLossPercent\":120}]}"))
                .andExpect(status().isBadRequest());

        String summaryUrl = reportsUrl(storeId) + "/period-summary?from=2026-09-21&to=2026-09-27";
        String json = mvc.perform(get(summaryUrl).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetsSet").value(true))
                .andExpect(jsonPath("$.lastSaleDate").value("2026-09-22"))
                .andExpect(jsonPath("$.documentsCoveredUntil").value("2026-09-27"))
                .andExpect(jsonPath("$.total.saleValueGross").value(429.0))
                .andExpect(jsonPath("$.total.lossValue").value(12.5))
                .andExpect(jsonPath("$.total.marginAfterLossesValue").value(67.5))
                .andReturn().getResponse().getContentAsString();

        // Kolejność wydruku: spożywcze > świeże > tradycyjne (owoce) ... suche (delikatesy) ... pozostałe.
        java.util.List<String> names = JsonPath.read(json, "$.lines[*].name");
        org.assertj.core.api.Assertions.assertThat(names).containsExactly(
                "Artykuły spożywcze", "Artykuły świeże", "Świeże tradycyjne", "OWOCE/WARZYWA/ROSLINY",
                "Razem świeże tradycyjne", "Razem artykuły świeże", "Artykuły suche", null, "Razem artykuły suche",
                "Razem artykuły spożywcze", "Pozostałe", "Bez nomenklatury", "Razem pozostałe");

        mvc.perform(get(summaryUrl).cookie(owner))
                .andExpect(jsonPath("$.lines[3].kind").value("REGION"))
                .andExpect(jsonPath("$.lines[3].code").value("14"))
                .andExpect(jsonPath("$.lines[3].depth").value(3))
                .andExpect(jsonPath("$.lines[3].figures.marginPercent").value(20.0))
                .andExpect(jsonPath("$.lines[3].figures.lossValue").value(10.5))
                .andExpect(jsonPath("$.lines[3].figures.lossPercent").value(10.0))
                .andExpect(jsonPath("$.lines[3].figures.marginAfterLossesPercent").value(9.5))
                .andExpect(jsonPath("$.lines[3].marginBudgetPercent").value(20.4))
                .andExpect(jsonPath("$.lines[3].maxLossPercent").value(4.0))
                .andExpect(jsonPath("$.lines[7].code").value("01"))
                .andExpect(jsonPath("$.lines[7].figures.sharePercent").value(75.52))
                .andExpect(jsonPath("$.lines[0].kind").value("GROUP"))
                .andExpect(jsonPath("$.lines[0].figures").value(nullValue()))
                .andExpect(jsonPath("$.lines[9].kind").value("SUBTOTAL"))
                .andExpect(jsonPath("$.lines[9].figures.saleValueNet").value(400.0))
                .andExpect(jsonPath("$.lines[11].code").value(""))
                .andExpect(jsonPath("$.lines[11].figures.lossValue").value(2.0))
                .andExpect(jsonPath("$.lines[11].figures.lossPercent").value(nullValue()));

        byte[] pdf = mvc.perform(get(reportsUrl(storeId) + "/period-summary/pdf?from=2026-09-21&to=2026-09-27")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        mvc.perform(get(reportsUrl(storeId) + "/period-summary?from=2026-09-27&to=2026-09-21").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    private static String reportsUrl(String storeId) {
        return "/api/stores/" + storeId + "/reports";
    }

    private String insertProduct(String storeId, String importId, String name, String nomenclature) {
        String itm8 = "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, unit, net_content, purchase_price,
                    sale_price, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, 'szt.', 1, 1, 9.99, true, ?::uuid, ?::uuid)""",
                storeId, itm8, "59" + itm8.substring(2), name, nomenclature, importId, importId);
        return itm8;
    }

    private void insertSale(String storeId, String importId, String date, String itm8, String cost, String gross,
            String vat, String margin) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?::date, ?, null, 1, ?, ?, ?, ?, ?::uuid)""",
                storeId, date, itm8, new BigDecimal(cost), new BigDecimal(gross), new BigDecimal(vat),
                new BigDecimal(margin), importId);
    }

    private void insertLine(String storeId, String importId, String type, String number, String nomenclature,
            String purchaseValue) {
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, timestamp '2026-09-23 12:00', 1, ?, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, new BigDecimal(purchaseValue), importId, importId);
        jdbc.update("""
                insert into document_lines (document_id, line_number, nomenclature_code, itm8, quantity,
                    purchase_value)
                values (?::uuid, 1, ?, '0000000000001', 1, ?)""",
                documentId, nomenclature, new BigDecimal(purchaseValue));
    }

    private String createStore(Cookie owner) throws Exception {
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
