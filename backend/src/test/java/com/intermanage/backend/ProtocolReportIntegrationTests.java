package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class ProtocolReportIntegrationTests {

    private static final String WASA = "0000000123456";
    private static final String BREAD = "0000000654321";

    /** Październik 2026 porównany z wrześniem. */
    private static final String PERIODS = "from=2026-10-01&to=2026-10-31&previousFrom=2026-09-01&previousTo=2026-09-30";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void groupsProtocolLinesAndComparesPeriods() throws Exception {
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
                values (?::uuid, 'Zeszyt2.xlsx', timestamp '2026-11-01 08:00:00', date '2026-08-01',
                    date '2026-10-31', 5, 5, 5, 0, 0) returning id""", String.class, storeId);

        String october = insertDocument(storeId, importId, "PL", "PL01", "2026-10-01 10:00");
        insertLine(october, 1, WASA, "WASA", "01 01 01", "2", "-4", "6");
        insertLine(october, 2, BREAD, null, null, "1", "-3", "5");
        String september = insertDocument(storeId, importId, "PL", "PL02", "2026-09-15 10:00");
        insertLine(september, 1, WASA, "WASA", "01 01 01", "1", "-2", "3");
        // Inny protokół nie wchodzi do likwidacji.
        insertLine(insertDocument(storeId, importId, "RWREP", "RW01", "2026-10-02 10:00"), 1, WASA, "WASA",
                "01 01 01", "9", "-90", "100");
        // Ostatnim dostawcą Wasy jest dostawca z przyjęcia, chleba nikt nie przywiózł (dostawca z likwidacji).
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, quantity, purchase_value, supplier_code,
                    supplier_name)
                values (?::uuid, 1, ?, 5, 10, '777', 'NOWY    DOSTAWCA')""",
                insertDocument(storeId, importId, "PZ", "999300", "2026-08-01 00:00"), WASA);

        String base = "/api/stores/" + storeId + "/reports/protocols";
        mvc.perform(get(base).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocols.length()").value(6))
                .andExpect(jsonPath("$.protocols[0].kind").value("LIQUIDATION"))
                .andExpect(jsonPath("$.protocols[0].documentType").value("PL"))
                .andExpect(jsonPath("$.protocols[2].kind").value("ADVERTISING"))
                .andExpect(jsonPath("$.protocols[2].documentType").doesNotExist())
                .andExpect(jsonPath("$.documentsCoveredUntil").value("2026-10-31"));

        mvc.perform(get(base + "/LIQUIDATION?grouping=ARTICLE&" + PERIODS).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.purchaseValue").value(7))
                .andExpect(jsonPath("$.total.saleValueGross").value(11))
                .andExpect(jsonPath("$.previousTotal.purchaseValue").value(2))
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].key").value(WASA))
                .andExpect(jsonPath("$.groups[0].current.quantity").value(2))
                .andExpect(jsonPath("$.groups[0].previous.quantity").value(1))
                .andExpect(jsonPath("$.groups[1].key").value(BREAD));

        mvc.perform(get(base + "/LIQUIDATION?grouping=SUPPLIER&" + PERIODS).cookie(owner))
                .andExpect(jsonPath("$.groups[0].code").value("777"))
                .andExpect(jsonPath("$.groups[0].name").value("NOWY DOSTAWCA"))
                .andExpect(jsonPath("$.groups[1].code").value("501"));

        mvc.perform(get(base + "/LIQUIDATION?grouping=BRAND&from=2026-10-01&to=2026-10-31").cookie(owner))
                .andExpect(jsonPath("$.previousTotal").doesNotExist())
                .andExpect(jsonPath("$.groups[0].name").value("WASA"))
                .andExpect(jsonPath("$.groups[0].previous").doesNotExist())
                .andExpect(jsonPath("$.groups[1].name").value("Bez marki"));

        mvc.perform(get(base + "/LIQUIDATION?grouping=NOMENCLATURE&level=REGION&" + PERIODS).cookie(owner))
                .andExpect(jsonPath("$.groups[0].code").value("01"))
                .andExpect(jsonPath("$.groups[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.groups[0].articles[0].itm8").value(WASA))
                .andExpect(jsonPath("$.groups[1].name").value("Bez nomenklatury"));

        mvc.perform(get(base + "/REPRESENTATION?grouping=NOMENCLATURE&level=SUBFAMILY&" + PERIODS).cookie(owner))
                .andExpect(jsonPath("$.groups[0].code").value("010101"))
                .andExpect(jsonPath("$.total.purchaseValue").value(90));

        // Protokół wyłączony w Ustawieniach sklepu znika z wyboru i nie da się z niego zrobić raportu.
        mvc.perform(put("/api/stores/" + storeId + "/proto-settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hiddenProtocols\":[\"LIQUIDATION\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get(base).cookie(owner))
                .andExpect(jsonPath("$.protocols.length()").value(5))
                .andExpect(jsonPath("$.protocols[0].kind").value("STORE_NEEDS"));
        mvc.perform(get(base + "/LIQUIDATION?grouping=ARTICLE&" + PERIODS).cookie(owner))
                .andExpect(status().isBadRequest());
    }

    private String insertDocument(String storeId, String importId, String type, String number, String date) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, date, importId, importId);
    }

    private void insertLine(String documentId, int lineNumber, String itm8, String brand, String nomenclature,
            String quantity, String purchaseValue, String saleValueGross) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, name, brand, nomenclature_code, quantity,
                    purchase_value, sale_value_gross, supplier_code, supplier_name)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, '501', 'WASA')""",
                documentId, lineNumber, itm8, "ARTYKUL " + itm8, brand, nomenclature, new BigDecimal(quantity),
                new BigDecimal(purchaseValue), new BigDecimal(saleValueGross));
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep protokołów\",\"active\":true}"))
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
