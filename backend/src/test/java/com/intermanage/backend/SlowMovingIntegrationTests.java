package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;

import jakarta.servlet.http.Cookie;

/** Niesprzedające się artykuły: rotacja tygodniowa w okresie, filtry, dostawy z takimi artykułami i wydruk. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class SlowMovingIntegrationTests {

    private static final String SLOW = "0000000000501";
    private static final String UNSOLD = "0000000000502";
    private static final String OTHER_REGION = "0000000000503";
    private static final String FAST = "0000000000504";
    private static final String NO_STOCK = "0000000000505";
    private static final String INACTIVE = "0000000000506";
    private static final String BAG = "0000000000507";
    private static final String RAW_MATERIAL = "0000000000508";
    private static final String LOCAL_ITM8 = "9900000000503";
    private static final String OTHER_REGION_EAN = "5900000000031";

    private final LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    private final LocalDate to = today.minusDays(1);
    /** 28 dni, więc próg 0,25 na tydzień to 1 sztuka w okresie. */
    private final LocalDate from = to.minusDays(27);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String productImport;
    private String salesImport;
    private String documentImport;
    private String category;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', '2026-11-05 06:30', 1, 1, 0, 0, 0) returning id""",
                String.class, store);
        salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-11-01 07:00:00', date '2026-07-01',
                    date '2026-10-31', 1, 1, 0, 0) returning id""", String.class, store);
        documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 2, 2, 2, 0, 0) returning id""", String.class, store);
        category = jdbc.queryForObject("""
                insert into price_check_categories (store_id, name, region_codes)
                values (?::uuid, 'Spożywka', '{01}') returning id""", String.class, store);

        product(SLOW, "5900000000017", "BUDYN", "01 01 01", "10", "5", true);
        product(UNSOLD, "5900000000024", "CHLEB", "01 02 01", "4", "30", true);
        product(OTHER_REGION, OTHER_REGION_EAN, "SER", "02 01 01", "1", "2", true);
        product(FAST, "5900000000048", "HIT", "01 01 01", "5", "1", true);
        product(NO_STOCK, "5900000000055", "BRAK", "01 01 01", "0", "1", true);
        product(BAG, "5900000000079", "TORBA", "60 01 01", "5", "1", true);
        sale(from, SLOW, "1");
        sale(to, FAST, "2");
        sale(from.minusDays(1), UNSOLD, "50");
        jdbc.update("""
                insert into cadencier_imports (store_id, file_name, report_generated_at, total_rows)
                values (?::uuid, 'obecne w cad.xlsx', '2026-11-04 15:02', 1)""", store);
        jdbc.update("""
                insert into cadencier_items (store_id, itm8, ean, import_id)
                values (?::uuid, ?, null, (select id from cadencier_imports where store_id = ?::uuid))""",
                store, UNSOLD, store);
        delivery("90", today.minusMonths(2), SLOW, null, "1", "5");
        delivery("100", today.minusDays(3), SLOW, null, "4", "20");
        delivery("190", today.minusMonths(2), LOCAL_ITM8, OTHER_REGION_EAN, "1", "2");
        delivery("200", today.minusDays(20), LOCAL_ITM8, OTHER_REGION_EAN, "1", "2");
    }

    @Test
    void listsSlowArticlesOnStockFromHighestStockValue() throws Exception {
        mvc.perform(report())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.unsoldCount").value(2))
                .andExpect(jsonPath("$.stockValue").value(172.0))
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD, SLOW, OTHER_REGION)))
                .andExpect(jsonPath("$.items[1].soldQuantity").value(1.0))
                .andExpect(jsonPath("$.items[1].weeklyRotation").value(0.25))
                .andExpect(jsonPath("$.items[1].supplierName").value("HURTOWNIA"))
                .andExpect(jsonPath("$.items[1].lastDeliveryDate").value(today.minusDays(3).toString()))
                // Linia z lokalnym ITM8 dostawcy należy do artykułu po EAN-ie.
                .andExpect(jsonPath("$.items[2].lastDeliveryDate").value(today.minusDays(20).toString()))
                .andExpect(jsonPath("$.deliveries", hasSize(0)));
    }

    @Test
    void thresholdIsWeeklyRotationOverChosenPeriod() throws Exception {
        mvc.perform(report().param("maxRotation", "0.5"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(UNSOLD, SLOW, OTHER_REGION, FAST)));
        mvc.perform(report().param("maxRotation", "0"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(UNSOLD, OTHER_REGION)));
        // Tydzień: ta sama 1 sztuka to już rotacja 1, a sprzedaż sprzed okresu się nie liczy.
        mvc.perform(get(url()).cookie(owner).param("from", from.toString()).param("to", from.plusDays(6).toString())
                        .param("sort", "itm8"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(UNSOLD, OTHER_REGION, FAST)));
    }

    @Test
    void filtersByNomenclatureCategoryAndOthers() throws Exception {
        mvc.perform(report().param("nomenclatures", "0101", "02"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(SLOW, OTHER_REGION)));
        mvc.perform(report().param("category", category))
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD, SLOW)))
                .andExpect(jsonPath("$.allCount").value(3))
                .andExpect(jsonPath("$.otherCount").value(1))
                .andExpect(jsonPath("$.categoryCounts[0].count").value(2));
        mvc.perform(report().param("category", "OTHER"))
                .andExpect(jsonPath("$.items[*].itm8", contains(OTHER_REGION)));
        mvc.perform(report().param("cadencier", "WITH"))
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD)));
        mvc.perform(report().param("minStockValue", "50").param("supplier", "7"))
                .andExpect(jsonPath("$.items[*].itm8", contains(SLOW)))
                .andExpect(jsonPath("$.suppliers[0].count").value(1));
        mvc.perform(report().param("query", "bud"))
                .andExpect(jsonPath("$.items[*].itm8", contains(SLOW)));
        mvc.perform(report().param("withoutDeliveries", "true"))
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD)));
    }

    @Test
    void hidesRawMaterialsUnlessAskedFor() throws Exception {
        product(RAW_MATERIAL, "5900000000086", "MIESO KL II", "24 03 01", "100", "10", true);
        mvc.perform(report())
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD, SLOW, OTHER_REGION)));
        mvc.perform(report().param("withRawMaterials", "true"))
                .andExpect(jsonPath("$.items[0].itm8").value(RAW_MATERIAL));
        mvc.perform(report().param("nomenclatures", "24"))
                .andExpect(jsonPath("$.items[*].itm8", contains(RAW_MATERIAL)));
    }

    @Test
    void showsDeliveriesWithSuchArticles() throws Exception {
        mvc.perform(report().param("deliveryDays", "7"))
                .andExpect(jsonPath("$.items[*].itm8", contains(SLOW)))
                .andExpect(jsonPath("$.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.deliveries[0].documentNumber").value("100"))
                .andExpect(jsonPath("$.deliveries[0].purchaseValue").value(20.0))
                .andExpect(jsonPath("$.deliveries[0].items[0].quantity").value(4.0));
        mvc.perform(report().param("deliveryDays", "30"))
                .andExpect(jsonPath("$.deliveries[*].documentNumber", contains("100", "200")))
                .andExpect(jsonPath("$.deliveries[1].items[0].itm8").value(OTHER_REGION));
        mvc.perform(report().param("deliveryDays", "1"))
                .andExpect(jsonPath("$.itemCount").value(0));
        mvc.perform(report().param("deliveryDays", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void articleInactiveInCatalogStaysInReport() throws Exception {
        product(INACTIVE, "5900000000062", "WYCOFANY", "01 01 01", "5", "1", false);

        mvc.perform(report()).andExpect(jsonPath("$.items[*].itm8",
                containsInAnyOrder(UNSOLD, SLOW, OTHER_REGION, INACTIVE)));
    }

    @Test
    void filtersByOriginOfLastDelivery() throws Exception {
        jdbc.update("""
                update document_lines set supplier_code = '538'
                where itm8 = ? and document_id in (select id from documents where store_id = ?::uuid)""",
                SLOW, store);

        mvc.perform(report().param("origin", "OUTSIDE"))
                .andExpect(jsonPath("$.items[*].itm8", contains(OTHER_REGION)));
        mvc.perform(report().param("origin", "BASE"))
                .andExpect(jsonPath("$.items[*].itm8", contains(SLOW)));
    }

    @Test
    void firstDeliveryMustBeAtLeastMonthOld() throws Exception {
        String monthOld = "0000000000511";
        String tooNew = "0000000000512";
        String newLocal = "9900000000512";
        String newLocalArticle = "0000000000513";
        product(monthOld, "5900000000086", "MIESIAC", "01 01 01", "1", "1", true);
        product(tooNew, "5900000000093", "NOWY", "01 01 01", "1", "1", true);
        product(newLocalArticle, "5900000000109", "NOWY LOKALNY", "01 01 01", "1", "1", true);
        delivery("301", today.minusMonths(1), monthOld, null, "1", "1");
        delivery("302", today.minusMonths(1).plusDays(1), tooNew, null, "1", "1");
        // Pierwsza dostawa pod lokalnym ITM8 dostawcy też się liczy.
        delivery("303", today.minusDays(2), newLocal, "5900000000109", "1", "1");

        mvc.perform(report())
                .andExpect(jsonPath("$.items[*].itm8",
                        containsInAnyOrder(UNSOLD, SLOW, OTHER_REGION, monthOld)));
    }

    @Test
    void excludedArticlesLeaveReportAndCanReturn() throws Exception {
        String excluded = mvc.perform(exclude(SLOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itm8").value(SLOW))
                .andExpect(jsonPath("$.name").value("BUDYN"))
                .andExpect(jsonPath("$.excludedBy").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(excluded, "$.id");

        // Drugie kliknięcie zostawia ten sam wpis.
        mvc.perform(exclude(SLOW)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(report())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[*].itm8", contains(UNSOLD, OTHER_REGION)));
        mvc.perform(get(excludedPath()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].itm8", contains(SLOW)));

        mvc.perform(delete(excludedPath() + "/" + id).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(excludedPath()).cookie(owner)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(report()).andExpect(jsonPath("$.itemCount").value(3));
        // Wpis ze znacznikiem zostaje jako historia, a artykuł da się pominąć ponownie.
        assertThat(jdbc.queryForObject(
                "select count(*) from slow_moving_excluded_articles where id = ?::uuid and removed_at is not null",
                Long.class, id)).isEqualTo(1);
        mvc.perform(exclude(SLOW)).andExpect(status().isOk());
        mvc.perform(report()).andExpect(jsonPath("$.itemCount").value(2));
    }

    @Test
    void articleOutsideStoreCatalogCannotBeExcluded() throws Exception {
        mvc.perform(exclude("0000000000999")).andExpect(status().isNotFound());
    }

    @Test
    void printsPdf() throws Exception {
        for (String deliveryDays : new String[] {"", "30"}) {
            byte[] pdf = mvc.perform(get(url() + "/pdf").cookie(owner).param("from", from.toString())
                            .param("to", to.toString()).param("deliveryDays", deliveryDays))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/pdf"))
                    .andReturn().getResponse().getContentAsByteArray();
            try (PdfReader reader = new PdfReader(pdf)) {
                assertThat(reader.getNumberOfPages()).isEqualTo(1);
            }
        }
    }

    private MockHttpServletRequestBuilder report() {
        return get(url()).cookie(owner).param("from", from.toString()).param("to", to.toString());
    }

    private String url() {
        return "/api/stores/" + store + "/reports/slow-moving";
    }

    private String excludedPath() {
        return "/api/stores/" + store + "/reports/slow-moving/excluded";
    }

    private MockHttpServletRequestBuilder exclude(String itm8) {
        return post(excludedPath()).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itm8\":\"" + itm8 + "\"}");
    }

    private void product(String itm8, String ean, String name, String nomenclature, String stock, String price,
            boolean active) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, stock, purchase_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?::uuid, ?::uuid)""",
                store, itm8, ean, name, nomenclature, stock, price, active, productImport, productImport);
    }

    private void sale(LocalDate day, String itm8, String quantity) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?, ?, ?, ?::numeric, 0, 0, 0, 0, ?::uuid)""",
                store, day, itm8, itm8, quantity, salesImport);
    }

    private void delivery(String number, LocalDate day, String itm8, String ean, String quantity, String value) {
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?::timestamp, 1, ?::numeric, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, number, day.toString(), value, documentImport, documentImport);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, quantity, purchase_value,
                    supplier_code, supplier_name)
                values (?::uuid, 1, ?, ?, ?::numeric, ?::numeric, ?, 'HURTOWNIA')""",
                UUID.fromString(documentId), itm8, ean, quantity, value, itm8.equals(SLOW) ? "7" : "8");
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
