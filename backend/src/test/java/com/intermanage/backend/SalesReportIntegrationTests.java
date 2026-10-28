package com.intermanage.backend;

import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class SalesReportIntegrationTests {

    /** Poprzedni okres 29-30.09, aktualny 1-2.10.2026. */
    private static final String PERIODS = "previousFrom=2026-09-29&previousTo=2026-09-30"
            + "&currentFrom=2026-10-01&currentTo=2026-10-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void comparesTwoPeriodsDownTheNomenclatureTree() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 3, 3, 0, 0, 0) returning id""", String.class, storeId);
        String bread = insertProduct(storeId, productImport, "PIECZYWO WASA", "01 01 01");
        String light = insertProduct(storeId, productImport, "PIECZYWO LEKKIE", "01 01 02");
        String ham = insertProduct(storeId, productImport, "SZYNKA", "02 01 01");
        // Nazwy ma tylko część nomenklatury: rejon 02 i podrodzina 010102 pokażą sam kod.
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 3, 3, 0, 0) returning id""", String.class, storeId);
        String region = insertNomenclature(storeId, nomenclatureImport, "01", null, "01_DELIKATESY");
        String family = insertNomenclature(storeId, nomenclatureImport, "0101", region, "PIECZYWO CHRUPKIE");
        insertNomenclature(storeId, nomenclatureImport, "010101", family, "PIECZYWO TWARDE");

        String salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-10-03 07:15:00', date '2026-09-01',
                    date '2026-10-02', 7, 7, 0, 0) returning id""", String.class, storeId);
        insertSale(storeId, salesImport, "2026-09-15", bread, "590001", "9", "18", "48.6", "3.6", "27");
        insertSale(storeId, salesImport, "2026-09-30", bread, "590001", "1", "2", "5.4", "0.4", "3");
        insertSale(storeId, salesImport, "2026-10-01", bread, "590001", "2", "4", "10.8", "0.8", "6");
        // Ten sam artykuł pod drugim EAN-em liczy się razem z pierwszym.
        insertSale(storeId, salesImport, "2026-10-02", bread, "590002", "1", "2", "5.4", "0.4", "3");
        insertSale(storeId, salesImport, "2026-10-01", light, "590003", "1", "1", "2.16", "0.16", "1");
        insertSale(storeId, salesImport, "2026-09-29", ham, "590004", "3", "3", "6.48", "0.48", "3");
        // Artykułu nie ma w kartotece, więc nie ma też nomenklatury.
        insertSale(storeId, salesImport, "2026-10-02", "0000099999999", null, "1", "1", "1.08", "0.08", "0");

        mvc.perform(get(reportsUrl(storeId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstSaleDate").value("2026-09-15"))
                .andExpect(jsonPath("$.lastSaleDate").value("2026-10-02"))
                .andExpect(jsonPath("$.lastReportGeneratedAt").value("2026-10-03T07:15:00"));

        compare(owner, storeId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("REGION"))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].code").value("01"))
                .andExpect(jsonPath("$.rows[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.rows[0].previous.quantity").value(1.0))
                .andExpect(jsonPath("$.rows[0].current.quantity").value(4.0))
                .andExpect(jsonPath("$.rows[0].current.costValue").value(7.0))
                .andExpect(jsonPath("$.rows[0].current.saleValueGross").value(18.36))
                .andExpect(jsonPath("$.rows[0].current.saleValueNet").value(17.0))
                .andExpect(jsonPath("$.rows[0].current.marginValue").value(10.0))
                .andExpect(jsonPath("$.rows[1].code").value("02"))
                .andExpect(jsonPath("$.rows[1].name").value(nullValue()))
                .andExpect(jsonPath("$.rows[1].previous.saleValueGross").value(6.48))
                .andExpect(jsonPath("$.rows[1].current.saleValueGross").value(0))
                // Sprzedaż bez nomenklatury na końcu, z pustym kodem.
                .andExpect(jsonPath("$.rows[2].code").value(""))
                .andExpect(jsonPath("$.rows[2].current.saleValueGross").value(1.08))
                .andExpect(jsonPath("$.previousTotal.saleValueGross").value(11.88))
                .andExpect(jsonPath("$.currentTotal.saleValueGross").value(19.44))
                .andExpect(jsonPath("$.currentTotal.marginValue").value(10.0));

        compare(owner, storeId, "01")
                .andExpect(jsonPath("$.level").value("FAMILY"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].code").value("0101"))
                .andExpect(jsonPath("$.rows[0].name").value("PIECZYWO CHRUPKIE"));
        compare(owner, storeId, "0101")
                .andExpect(jsonPath("$.level").value("SUBFAMILY"))
                .andExpect(jsonPath("$.rows[0].code").value("010101"))
                .andExpect(jsonPath("$.rows[0].name").value("PIECZYWO TWARDE"))
                .andExpect(jsonPath("$.rows[1].code").value("010102"))
                .andExpect(jsonPath("$.rows[1].name").value(nullValue()));
        compare(owner, storeId, "010101")
                .andExpect(jsonPath("$.level").value("PRODUCT"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].code").value(bread))
                .andExpect(jsonPath("$.rows[0].name").value("PIECZYWO WASA"))
                .andExpect(jsonPath("$.rows[0].previous.quantity").value(1.0))
                .andExpect(jsonPath("$.rows[0].current.quantity").value(3.0))
                .andExpect(jsonPath("$.currentTotal.saleValueGross").value(16.2));
        mvc.perform(get(comparisonUrl(storeId) + "&unassigned=true").cookie(owner))
                .andExpect(jsonPath("$.level").value("PRODUCT"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].code").value("0000099999999"))
                .andExpect(jsonPath("$.rows[0].name").value(nullValue()));

        // Filtr nomenklatury: tylko wybrane pozycje (dowolnego poziomu), bez sprzedaży bez nomenklatury.
        mvc.perform(get(comparisonUrl(storeId) + "&nomenclatures=010102").cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].code").value("01"))
                .andExpect(jsonPath("$.currentTotal.saleValueGross").value(2.16));
        mvc.perform(get(comparisonUrl(storeId) + "&nomenclatures=010101&nomenclatures=02").cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.previousTotal.saleValueGross").value(11.88))
                .andExpect(jsonPath("$.currentTotal.saleValueGross").value(16.2));
        mvc.perform(get(comparisonUrl(storeId) + "&nomenclatures=1").cookie(owner))
                .andExpect(status().isBadRequest());

        mvc.perform(get(reportsUrl(storeId) + "/nomenclatures").cookie(owner))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].code").value("0101"))
                .andExpect(jsonPath("$[1].level").value("FAMILY"));

        // Wykres: każdy dzień okresu, dni bez sprzedaży z zerami, artykuł ze wszystkich EAN-ów.
        mvc.perform(get(historyUrl(storeId) + "&itm8=" + bread).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(4))
                .andExpect(jsonPath("$.days[0].date").value("2026-09-29"))
                .andExpect(jsonPath("$.days[0].figures.quantity").value(0))
                .andExpect(jsonPath("$.days[3].figures.quantity").value(1.0))
                .andExpect(jsonPath("$.total.quantity").value(4.0));
        mvc.perform(get(historyUrl(storeId) + "&parent=01&nomenclatures=010102").cookie(owner))
                .andExpect(jsonPath("$.days[2].figures.saleValueGross").value(2.16))
                .andExpect(jsonPath("$.total.saleValueGross").value(2.16));
        mvc.perform(get(historyUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.total.saleValueGross").value(31.32));
        mvc.perform(get(historyUrl(storeId) + "&unassigned=true").cookie(owner))
                .andExpect(jsonPath("$.total.saleValueGross").value(1.08));

        jdbc.update("update products set stock = 12.5, rotation = 3, supplier = 'WASA' where itm8 = ?", bread);
        mvc.perform(get(reportsUrl(storeId) + "/articles/" + bread).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PIECZYWO WASA"))
                .andExpect(jsonPath("$.stock").value(12.5))
                .andExpect(jsonPath("$.rotation").value(3))
                .andExpect(jsonPath("$.supplier").value("WASA"))
                .andExpect(jsonPath("$.nomenclaturePath.length()").value(3))
                .andExpect(jsonPath("$.nomenclaturePath[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.nomenclaturePath[2].code").value("010101"));
        mvc.perform(get(reportsUrl(storeId) + "/articles/0000099999999").cookie(owner))
                .andExpect(status().isNotFound());

        // Zły kod nomenklatury, odwrócone daty i okres dłuższy niż rok.
        compare(owner, storeId, "1").andExpect(status().isBadRequest());
        mvc.perform(get(reportsUrl(storeId) + "/sales-comparison?previousFrom=2026-09-30&previousTo=2026-09-29"
                        + "&currentFrom=2026-10-01&currentTo=2026-10-02").cookie(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get(reportsUrl(storeId) + "/sales-comparison?previousFrom=2024-01-01&previousTo=2025-01-01"
                        + "&currentFrom=2026-10-01&currentTo=2026-10-02").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deliCostsReplaceFifoWithRepresentationProtocols() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 2, 2, 0, 0, 0) returning id""", String.class, storeId);
        String salad = insertProduct(storeId, productImport, "SAŁATKA JARZYNOWA", "36 01 01");
        String bread = insertProduct(storeId, productImport, "PIECZYWO WASA", "01 01 01");
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 1, 1, 0, 0) returning id""", String.class, storeId);
        insertNomenclature(storeId, nomenclatureImport, "36", null, "36_GARMAZ");
        String salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-10-03 07:15:00', date '2026-09-01',
                    date '2026-10-02', 3, 3, 0, 0) returning id""", String.class, storeId);
        insertSale(storeId, salesImport, "2026-09-30", salad, "590001", "2", "8", "21.6", "1.6", "5");
        insertSale(storeId, salesImport, "2026-10-01", salad, "590001", "5", "20", "54", "4", "30");
        // Sprzedaż spoza garmażu nie wchodzi do raportu.
        insertSale(storeId, salesImport, "2026-10-01", bread, "590002", "1", "1", "10.8", "0.8", "9");

        // Protokoły reprezentacji to wydania z magazynu: wartość zakupu z minusem.
        String documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', timestamp '2026-10-03 08:00:00', date '2026-09-01',
                    date '2026-10-02', 3, 3, 3, 0, 0) returning id""", String.class, storeId);
        insertDocument(storeId, documentImport, "RWREP", "RWREP01", "2026-09-29 10:00", "-6");
        insertDocument(storeId, documentImport, "RWREP", "RWREP02", "2026-10-02 23:30", "-12.5");
        insertDocument(storeId, documentImport, "PL", "PL01", "2026-10-01 10:00", "-100");

        mvc.perform(get(reportsUrl(storeId) + "/deli-costs").cookie(owner))
                .andExpect(jsonPath("$.documentsReportGeneratedAt").value("2026-10-03T08:00:00"))
                .andExpect(jsonPath("$.costsCoveredUntil").value("2026-10-02"));

        String deliUrl = reportsUrl(storeId) + "/deli-comparison?" + PERIODS;
        mvc.perform(get(deliUrl).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("REGION"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].code").value("36"))
                .andExpect(jsonPath("$.rows[0].name").value("36_GARMAZ"))
                .andExpect(jsonPath("$.rows[0].previous.costValue").value(6.0))
                .andExpect(jsonPath("$.rows[0].previous.saleValueNet").value(20.0))
                .andExpect(jsonPath("$.rows[0].previous.marginValue").value(14.0))
                .andExpect(jsonPath("$.rows[0].current.costValue").value(12.5))
                .andExpect(jsonPath("$.rows[0].current.saleValueGross").value(54.0))
                .andExpect(jsonPath("$.rows[0].current.marginValue").value(37.5))
                .andExpect(jsonPath("$.currentTotal.costValue").value(12.5))
                .andExpect(jsonPath("$.currentTotal.quantity").value(5.0));
        // Niżej kosztu nie da się przypisać: koszt i marża puste.
        mvc.perform(get(deliUrl + "&parent=36").cookie(owner))
                .andExpect(jsonPath("$.level").value("FAMILY"))
                .andExpect(jsonPath("$.rows[0].code").value("3601"))
                .andExpect(jsonPath("$.rows[0].current.costValue").value(nullValue()))
                .andExpect(jsonPath("$.rows[0].current.marginValue").value(nullValue()))
                .andExpect(jsonPath("$.rows[0].current.saleValueGross").value(54.0));
        mvc.perform(get(deliUrl + "&parent=01").cookie(owner)).andExpect(status().isBadRequest());

        mvc.perform(get(reportsUrl(storeId) + "/deli-history?from=2026-09-29&to=2026-10-02").cookie(owner))
                .andExpect(jsonPath("$.days.length()").value(4))
                .andExpect(jsonPath("$.days[0].figures.costValue").value(6.0))
                .andExpect(jsonPath("$.days[0].figures.marginValue").value(-6.0))
                .andExpect(jsonPath("$.days[3].figures.costValue").value(12.5))
                .andExpect(jsonPath("$.total.costValue").value(18.5))
                .andExpect(jsonPath("$.total.saleValueNet").value(70.0));
        mvc.perform(get(reportsUrl(storeId) + "/deli-history?from=2026-09-29&to=2026-10-02&itm8=" + salad)
                        .cookie(owner))
                .andExpect(jsonPath("$.total.costValue").value(nullValue()))
                .andExpect(jsonPath("$.total.quantity").value(7.0));
    }

    private void insertDocument(String storeId, String importId, String type, String number, String date,
            String purchaseValue) {
        jdbc.update("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, 1, ?, 0, ?::uuid, ?::uuid)""",
                storeId, type, number, date, new BigDecimal(purchaseValue), importId, importId);
    }

    private ResultActions compare(Cookie owner, String storeId, String parent) throws Exception {
        return mvc.perform(get(comparisonUrl(storeId) + "&parent=" + parent).cookie(owner));
    }

    private static String comparisonUrl(String storeId) {
        return reportsUrl(storeId) + "/sales-comparison?" + PERIODS;
    }

    /** Wykres za 29.09-2.10.2026. */
    private static String historyUrl(String storeId) {
        return reportsUrl(storeId) + "/sales-history?from=2026-09-29&to=2026-10-02";
    }

    private static String reportsUrl(String storeId) {
        return "/api/stores/" + storeId + "/reports";
    }

    /** Produkt w kartotece sklepu, tak jakby przyszedł z importu raportu; zwraca ITM8. */
    private String insertProduct(String storeId, String importId, String name, String nomenclature) {
        String itm8 = "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, unit, net_content, purchase_price,
                    sale_price, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, 'szt.', 1, 1, 9.99, true, ?::uuid, ?::uuid)""",
                storeId, itm8, "59" + itm8.substring(2), name, nomenclature, importId, importId);
        return itm8;
    }

    private String insertNomenclature(String storeId, String importId, String code, String parentId, String name) {
        String level = switch (code.length()) {
            case 2 -> "REGION";
            case 4 -> "FAMILY";
            default -> "SUBFAMILY";
        };
        return jdbc.queryForObject("""
                insert into nomenclatures (store_id, code, level, parent_id, name, last_import_id)
                values (?::uuid, ?, ?, ?::uuid, ?, ?::uuid) returning id""",
                String.class, storeId, code, level, parentId, name, importId);
    }

    private void insertSale(String storeId, String importId, String date, String itm8, String ean, String quantity,
            String cost, String gross, String vat, String margin) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?::date, ?, ?, ?, ?, ?, ?, ?, ?::uuid)""",
                storeId, date, itm8, ean, new BigDecimal(quantity), new BigDecimal(cost), new BigDecimal(gross),
                new BigDecimal(vat), new BigDecimal(margin), importId);
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep raportów\",\"active\":true}"))
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
