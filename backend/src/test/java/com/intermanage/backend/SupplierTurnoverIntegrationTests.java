package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
class SupplierTurnoverIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sumsSupplierPurchasesByMonthAndNomenclatureAfterCorrections() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 1, 1, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into nomenclatures (store_id, code, level, name, last_import_id)
                values (?::uuid, '01', 'REGION', '01_DELIKATESY', ?::uuid)""", storeId, nomenclatureImport);
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', timestamp '2026-11-01 08:00:00', date '2026-09-01',
                    date '2026-10-31', 5, 5, 5, 0, 0) returning id""", String.class, storeId);

        String september = insertDocument(storeId, importId, "PZ", "100", "2026-09-10 10:00");
        insertLine(september, 1, "501", "01 01 01", "100");
        insertLine(september, 2, "501", "01 02 01", "50");
        String october = insertDocument(storeId, importId, "PZ", "200", "2026-10-05 10:00");
        insertLine(october, 1, "501", "01 01 01", "200");
        insertLine(october, 2, "501", null, "10");
        // Korekta wrześniowego przyjęcia wystawiona w październiku liczy się we wrześniu.
        String correction = insertDocument(storeId, importId, "WZ", "300", "2026-10-02 10:00");
        insertLine(correction, 1, "501", "01 01 01", "-20");
        jdbc.update("""
                insert into delivery_corrections (id, delivery_id, correction_id, linked_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now())""", september, correction);
        // Korekta bez przyjęcia nie wchodzi do kwot, raport ją tylko podaje.
        insertLine(insertDocument(storeId, importId, "WZ", "301", "2026-10-03 10:00"), 1, "501", "01 01 01", "-7");
        // Inny dostawca i błędne przyjęcie się nie liczą.
        insertLine(insertDocument(storeId, importId, "PZ", "400", "2026-10-06 10:00"), 1, "777", "01 01 01", "999");
        String error = insertDocument(storeId, importId, "PZ", "500", "2026-10-07 10:00");
        insertLine(error, 1, "501", "01 01 01", "888");
        jdbc.update("update documents set error_marked_at = now() where id = ?::uuid", error);

        String base = "/api/stores/" + storeId + "/reports/supplier-turnover";
        mvc.perform(get(base + "/suppliers").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("777"))
                .andExpect(jsonPath("$[1].code").value("501"))
                .andExpect(jsonPath("$[1].name").value("DOSTAWCA 501"));

        mvc.perform(get(base + "?supplier=501&from=2026-09-01&to=2026-10-31").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0]").value("2026-09-01"))
                .andExpect(jsonPath("$.months[1]").value("2026-10-01"))
                .andExpect(jsonPath("$.deliveries[0]").value(1))
                .andExpect(jsonPath("$.deliveries[1]").value(1))
                .andExpect(jsonPath("$.total.delivered").value(360))
                .andExpect(jsonPath("$.total.corrections").value(-20))
                .andExpect(jsonPath("$.total.net").value(340))
                .andExpect(jsonPath("$.totals[0].net").value(130))
                .andExpect(jsonPath("$.totals[1].net").value(210))
                .andExpect(jsonPath("$.rows[0].code").value("01"))
                .andExpect(jsonPath("$.rows[0].level").value("REGION"))
                .andExpect(jsonPath("$.rows[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.rows[0].values[0].net").value(130))
                .andExpect(jsonPath("$.rows[0].total.net").value(330))
                .andExpect(jsonPath("$.rows[1].code").value("0101"))
                .andExpect(jsonPath("$.rows[2].code").value("010101"))
                .andExpect(jsonPath("$.rows[2].values[0].net").value(80))
                .andExpect(jsonPath("$.rows[3].code").value("0102"))
                .andExpect(jsonPath("$.rows[5].code").value(""))
                .andExpect(jsonPath("$.rows[5].name").value("Bez nomenklatury"))
                .andExpect(jsonPath("$.rows[5].total.net").value(10))
                .andExpect(jsonPath("$.unattachedCorrections.count").value(1))
                .andExpect(jsonPath("$.unattachedCorrections.purchaseValue").value(-7));

        mvc.perform(get(base + "?supplier=&from=2026-09-01&to=2026-10-31").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    private String insertDocument(String storeId, String importId, String type, String number, String date) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, date, importId, importId);
    }

    private void insertLine(String documentId, int lineNumber, String supplierCode, String nomenclature,
            String purchaseValue) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, name, nomenclature_code, quantity,
                    purchase_value, sale_value_gross, supplier_code, supplier_name)
                values (?::uuid, ?, ?, 'ARTYKUL', ?, 1, ?, 0, ?, ?)""",
                documentId, lineNumber, "000000000000" + lineNumber, nomenclature, new BigDecimal(purchaseValue),
                supplierCode, "DOSTAWCA  " + supplierCode);
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep obrotów\",\"active\":true}"))
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
