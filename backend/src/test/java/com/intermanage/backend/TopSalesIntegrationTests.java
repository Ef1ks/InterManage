package com.intermanage.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import jakarta.servlet.http.Cookie;

/** Topy sprzedażowe: ilość bez promocji z niską marżą, marża zł i %, poziomy nomenklatury, sklepy zaprzyjaźnione. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class TopSalesIntegrationTests {

    private static final String BEST = "0000000000001";
    private static final String SECOND = "0000000000002";
    private static final String OTHER_FAMILY = "0000000000003";
    private static final String OTHER_REGION = "0000000000004";
    private static final String NOT_IN_CATALOG = "0000000000005";
    private static final String PERIOD = "from=2026-10-01&to=2026-10-31";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String salesImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        salesImport = salesImport(store);
        product(store, BEST, "010101");
        product(store, SECOND, "010101");
        product(store, OTHER_FAMILY, "010201");
        product(store, OTHER_REGION, "660101");
    }

    @Test
    void ranksByChosenMetricInGroupsInCodeOrder() throws Exception {
        sale(store, salesImport, BEST, "2026-10-05", 100, "100.00", "200.00");
        sale(store, salesImport, SECOND, "2026-10-05", 50, "50.00", "500.00");
        sale(store, salesImport, OTHER_FAMILY, "2026-10-05", 10, "10.00", "20.00");
        sale(store, salesImport, OTHER_REGION, "2026-10-05", 5, "5.00", "10.00");
        sale(store, salesImport, NOT_IN_CATALOG, "2026-10-05", 1, "1.00", "2.00");

        mvc.perform(get(url("metric=QUANTITY")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].code", contains("01", "66", "")))
                .andExpect(jsonPath("$.groups[0].name").value("01_DELIKATESY"))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(BEST, SECOND, OTHER_FAMILY)))
                .andExpect(jsonPath("$.groups[0].rows[0].rank").value(1));

        mvc.perform(get(url("metric=MARGIN&level=FAMILY")).cookie(owner))
                .andExpect(jsonPath("$.groups[*].code", contains("0101", "0102", "6601", "")))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(SECOND, BEST)));

        mvc.perform(get(url("metric=QUANTITY&level=STORE&limit=2")).cookie(owner))
                .andExpect(jsonPath("$.groups", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].code").isEmpty())
                .andExpect(jsonPath("$.groups[0].articleCount").value(5))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(BEST, SECOND)));

        // Filtr bierze kody dowolnego poziomu i odcina artykuły bez nomenklatury.
        mvc.perform(get(url("metric=QUANTITY&level=SUBFAMILY&nomenclatures=0102&nomenclatures=66")).cookie(owner))
                .andExpect(jsonPath("$.groups[*].code", contains("010201", "660101")));
    }

    @Test
    void quantityTopSkipsPromotionsWithLowMarginButMarginTopKeepsThem() throws Exception {
        sale(store, salesImport, BEST, "2026-10-05", 1000, "950.00", "1000.00");
        sale(store, salesImport, SECOND, "2026-10-05", 10, "5.00", "10.00");
        // Ta sama promocja z marżą 20% zostaje.
        sale(store, salesImport, OTHER_FAMILY, "2026-10-05", 100, "80.00", "100.00");
        promotion(BEST);
        promotion(OTHER_FAMILY);

        mvc.perform(get(url("metric=QUANTITY&level=STORE")).cookie(owner))
                .andExpect(jsonPath("$.maxPromotionMarginPercent").value(10))
                .andExpect(jsonPath("$.stores[0].promotionSkipped").value(1))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(OTHER_FAMILY, SECOND)));

        mvc.perform(get(url("metric=MARGIN&level=STORE")).cookie(owner))
                .andExpect(jsonPath("$.stores[0].promotionSkipped").value(0))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(BEST, OTHER_FAMILY, SECOND)));
    }

    @Test
    void marginTopsSkipZeroCostAndPercentTopNeedsMinimumQuantity() throws Exception {
        sale(store, salesImport, BEST, "2026-10-05", 10, "0.00", "100.00");
        sale(store, salesImport, SECOND, "2026-10-05", 10, "10.00", "100.00");
        sale(store, salesImport, OTHER_FAMILY, "2026-10-05", 20, "60.00", "100.00");
        sale(store, salesImport, OTHER_REGION, "2026-10-05", 1, "1.00", "100.00");

        mvc.perform(get(url("metric=MARGIN_PERCENT&level=STORE&minQuantity=5")).cookie(owner))
                .andExpect(jsonPath("$.stores[0].noCostSkipped").value(1))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(SECOND, OTHER_FAMILY)));
    }

    @Test
    void comparesWithFriendlyStoresOnly() throws Exception {
        String friend = createStore();
        String stranger = createStore();
        product(friend, BEST, "010101");
        jdbc.update("""
                insert into store_partners (store_id, partner_store_id, invited_at, accepted_at)
                values (?::uuid, ?::uuid, now(), now())""", friend, store);
        String friendImport = salesImport(friend);
        sale(store, salesImport, BEST, "2026-10-05", 10, "5.00", "10.00");
        sale(store, salesImport, SECOND, "2026-10-05", 5, "2.00", "10.00");
        sale(friend, friendImport, SECOND, "2026-10-05", 30, "2.00", "10.00");
        sale(friend, friendImport, BEST, "2026-10-05", 20, "5.00", "10.00");

        mvc.perform(get("/api/stores/" + store + "/reports/top-sales/stores").cookie(owner))
                .andExpect(jsonPath("$[*].id", contains(friend)));

        mvc.perform(get(url("metric=QUANTITY&level=STORE&compareStores=" + friend)).cookie(owner))
                .andExpect(jsonPath("$.stores[*].id", contains(store, friend)))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(BEST, SECOND)))
                .andExpect(jsonPath("$.groups[0].rows[0].others[0].storeId").value(friend))
                .andExpect(jsonPath("$.groups[0].rows[0].others[0].rank").value(2))
                .andExpect(jsonPath("$.groups[0].rows[0].others[0].quantity").value(20.0));

        mvc.perform(get(url("metric=QUANTITY&level=STORE&compareStores=" + friend + "&rankStore=" + friend))
                        .cookie(owner))
                .andExpect(jsonPath("$.rankStoreId").value(friend))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(SECOND, BEST)));

        mvc.perform(get(url("metric=QUANTITY&compareStores=" + stranger)).cookie(owner))
                .andExpect(status().isForbidden());
    }

    @Test
    void skipsRegionsThatAreNotGoods() throws Exception {
        sale(store, salesImport, BEST, "2026-10-05", 10, "5.00", "10.00");
        int number = 10;
        for (String nomenclature : new String[] {"600101", "610101", "900101", "910101"}) {
            String itm8 = "00000000000" + number++;
            product(store, itm8, nomenclature);
            sale(store, salesImport, itm8, "2026-10-05", 1000, "500.00", "1000.00");
        }

        mvc.perform(get(url("metric=QUANTITY&level=STORE")).cookie(owner))
                .andExpect(jsonPath("$.groups[0].rows[*].itm8", contains(BEST)));
        mvc.perform(get(url("metric=MARGIN&level=REGION")).cookie(owner))
                .andExpect(jsonPath("$.groups[*].code", contains("01")));
    }

    @Test
    void rejectsWrongLimit() throws Exception {
        mvc.perform(get(url("metric=QUANTITY&limit=0")).cookie(owner)).andExpect(status().isBadRequest());
    }

    private String url(String query) {
        return "/api/stores/" + store + "/reports/top-sales?" + PERIOD + "&" + query;
    }

    private String salesImport(String storeId) {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 1, 1, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into nomenclatures (store_id, code, level, name, last_import_id)
                values (?::uuid, '01', 'REGION', '01_DELIKATESY', ?::uuid)""", storeId, nomenclatureImport);
        return jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-11-01 07:00:00', date '2026-07-01',
                    date '2026-10-31', 1, 1, 0, 0) returning id""", String.class, storeId);
    }

    private void product(String storeId, String itm8, String subfamily) {
        String code = subfamily.substring(0, 2) + " " + subfamily.substring(2, 4) + " " + subfamily.substring(4);
        String importId = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, active, first_seen_import_id,
                    last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, true, ?::uuid, ?::uuid)""",
                storeId, itm8, itm8, "ARTYKUL " + itm8, code, importId, importId);
    }

    private void promotion(String itm8) {
        String importId = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, report_generated_at, end_from, end_to, total_rows)
                values (?::uuid, 'Taryfy.pdf', timestamp '2026-10-01 06:00:00', date '2026-10-01',
                    date '2026-12-31', 1) returning id""", String.class, store);
        jdbc.update("""
                insert into promotions (store_id, itm8, name, start_date, end_date, regular_sale_price, promo_price,
                    margin_percent, first_import_id, last_import_id)
                values (?::uuid, ?, 'PROMOCJA', date '2026-10-01', date '2026-10-10', 10, 8, 5, ?::uuid, ?::uuid)""",
                store, itm8, importId, importId);
    }

    /** Sprzedaż bez VAT, więc brutto = netto. */
    private void sale(String storeId, String importId, String itm8, String day, int quantity, String cost,
            String gross) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?::date, ?, ?, ?, ?::numeric, ?::numeric, 0, ?::numeric - ?::numeric, ?::uuid)""",
                storeId, day, itm8, itm8, quantity, cost, gross, gross, cost, importId);
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
