package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import jakarta.servlet.http.Cookie;

/** Braki towaru: typowy tydzień, cisza w sprzedaży, rodzaje braków i warunek dostawy. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StockGapIntegrationTests {

    private static final String MISSING = "0000000000701";
    private static final String ONE_OFF = "0000000000702";
    private static final String RUNNING_OUT = "0000000000703";
    private static final String WRONG_STOCK = "0000000000704";
    private static final String WELL_STOCKED = "0000000000705";
    private static final String RARE = "0000000000706";
    private static final String PROMO_SPIKE = "0000000000707";
    private static final String PRODUCTION = "0000000000708";
    private static final String BAG = "0000000000709";
    private static final String DELIVERED = "0000000000710";
    private static final String SILENT_STOCKED = "0000000000711";
    private static final String RECEIVED_FROM_STORE = "0000000000712";
    private static final String LOCAL_ITM8 = "9900000000710";

    /** Ostatni dzień sprzedaży: stała data, bo liczy się od wgranej sprzedaży, a nie od dziś. */
    private final LocalDate last = LocalDate.of(2026, 10, 31);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String productImport;
    private String salesImport;
    private String documentImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', '2026-11-01 06:30', 1, 1, 0, 0, 0) returning id""",
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

        // 7 szt. w tygodniu, czyli 1 dziennie; cisza 5 dni to 5 niesprzedanych sztuk.
        article(MISSING, "01 01 01", "0", 5);
        deliveries(MISSING, 3);
        article(ONE_OFF, "01 01 01", "1", 5);
        article(RUNNING_OUT, "01 01 01", "2", 0);
        deliveries(RUNNING_OUT, 3);
        article(WRONG_STOCK, "01 01 01", "-12", 0);
        article(WELL_STOCKED, "01 01 01", "40", 0);
        article(SILENT_STOCKED, "01 01 01", "30", 5);
        article(PRODUCTION, "36 01 01", "-500", 5);
        article(BAG, "60 01 01", "0", 5);
        article(DELIVERED, "01 01 01", "0", 5);
        deliveries(DELIVERED, 3);
        delivery("D1", last.minusDays(3), LOCAL_ITM8, "5900000000710", "PZ", "4");
        article(RECEIVED_FROM_STORE, "01 01 01", "0", 5);
        deliveries(RECEIVED_FROM_STORE, 3);
        delivery("M1", last.minusDays(2), RECEIVED_FROM_STORE, null, "MM", "-6");

        // 1 szt. tygodniowo: mimo stanu 0 to nie jest dobra sprzedaż.
        product(RARE, "01 01 01", "0");
        for (int week = 0; week < 8; week++) {
            sale(last.minusDays(5 + week * 7L), RARE, "1");
        }
        // Dwa tygodnie po 100 szt. z gazetki przy 1 szt. w pozostałych: mediana to 1, średnia byłaby ponad 25.
        product(PROMO_SPIKE, "01 01 01", "0");
        for (int week = 0; week < 8; week++) {
            sale(last.minusDays(5 + week * 7L), PROMO_SPIKE, week < 2 ? "100" : "1");
        }
    }

    @Test
    void splitsSellingArticlesIntoKinds() throws Exception {
        mvc.perform(report())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSaleDate").value(last.toString()))
                .andExpect(jsonPath("$.items[?(@.kind == 'MISSING')].itm8", containsInAnyOrder(MISSING)))
                .andExpect(jsonPath("$.items[?(@.kind == 'ONE_OFF')].itm8", contains(ONE_OFF)))
                .andExpect(jsonPath("$.items[?(@.kind == 'RUNNING_OUT')].itm8", contains(RUNNING_OUT)))
                .andExpect(jsonPath("$.items[?(@.kind == 'WRONG_STOCK')].itm8", contains(WRONG_STOCK)));
    }

    @Test
    void measuresTypicalWeekSilenceAndLostMargin() throws Exception {
        mvc.perform(report())
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].typicalWeek").value(7.0))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].weeksSold").value(8))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].silentDays").value(5))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].missedQuantity").value(5.0))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].weeklyMargin").value(14.0))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + RUNNING_OUT + "')].coverDays").value(2.0));
    }

    @Test
    void skipsWhatIsNotAGap() throws Exception {
        String json = mvc.perform(report()).andReturn().getResponse().getContentAsString();
        for (String itm8 : new String[] {WELL_STOCKED, RARE, PROMO_SPIKE, PRODUCTION, BAG, SILENT_STOCKED}) {
            assertThat(json).doesNotContain(itm8);
        }
    }

    @Test
    void recentDeliveryHidesGapUntilConditionIsLifted() throws Exception {
        // Dostawa pod lokalnym ITM8 (po EAN-ie) i towar od innego sklepu też się liczą.
        mvc.perform(report()).andExpect(jsonPath("$.items[?(@.itm8 == '" + DELIVERED + "')]", hasSize(0)))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + RECEIVED_FROM_STORE + "')]", hasSize(0)));
        mvc.perform(report().param("noDeliveryDays", "0"))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + DELIVERED + "')].lastDeliveryDate")
                        .value(last.minusDays(3).toString()))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + RECEIVED_FROM_STORE + "')].lastDeliveryDate")
                        .value(last.minusDays(2).toString()));
    }

    @Test
    void thresholdsAndNomenclatureAreAdjustable() throws Exception {
        mvc.perform(report().param("minWeekly", "8")).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(report().param("maxStock", "0"))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + ONE_OFF + "')]", hasSize(0)));
        mvc.perform(report().param("nomenclatures", "02")).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(report().param("minWeekly", "0")).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder report() {
        return get("/api/stores/" + store + "/reports/stock-gaps").cookie(owner);
    }

    /** Sprzedaż 1 szt. dziennie przez 8 tygodni, skończona {@code silentDays} dni przed ostatnim dniem sprzedaży. */
    private void article(String itm8, String nomenclature, String stock, int silentDays) {
        product(itm8, nomenclature, stock);
        LocalDate lastSale = last.minusDays(silentDays);
        for (int day = 0; day < 56; day++) {
            sale(lastSale.minusDays(day), itm8, "1");
        }
    }

    private void deliveries(String itm8, int count) {
        for (int i = 0; i < count; i++) {
            delivery(itm8 + "-" + i, last.minusDays(30 + i * 20L), itm8, null, "PZ", "20");
        }
    }

    private void product(String itm8, String nomenclature, String stock) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, stock, purchase_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?::numeric, 1, true, ?::uuid, ?::uuid)""",
                store, itm8, "590000000" + itm8.substring(9), "ARTYKUL " + itm8, nomenclature, stock, productImport,
                productImport);
    }

    private void sale(LocalDate day, String itm8, String quantity) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?, ?, ?, ?::numeric, 0, 0, 0, ?::numeric * 2, ?::uuid)""",
                store, day, itm8, itm8, quantity, quantity, salesImport);
    }

    private void delivery(String number, LocalDate day, String itm8, String ean, String type, String quantity) {
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, 1, 10, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, type, number, day.toString(), documentImport, documentImport);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, quantity, purchase_value,
                    supplier_code, supplier_name)
                values (?::uuid, 1, ?, ?, ?::numeric, 10, '7', 'HURTOWNIA')""",
                UUID.fromString(documentId), itm8, ean, quantity);
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
