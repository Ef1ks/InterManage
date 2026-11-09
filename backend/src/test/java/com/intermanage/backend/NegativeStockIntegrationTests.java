package com.intermanage.backend;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
import com.intermanage.backend.common.component.StoreTime;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Ujemne stany: warunki dostaw, dostawa pod lokalnym ITM8, produkcja i artykuły, które nie są towarem. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class NegativeStockIntegrationTests {

    private static final String NEVER_DELIVERED = "0000000000801";
    private static final String OLD_DELIVERY = "0000000000802";
    private static final String RECENT_DELIVERY = "0000000000803";
    private static final String LOCAL_DELIVERY = "0000000000804";
    private static final String POSITIVE = "0000000000805";
    private static final String PRODUCTION = "0000000000806";
    private static final String BAG = "0000000000807";
    private static final String LOCAL_ITM8 = "9900000000804";

    /** Dni bez dostawy liczy się od dziś, więc daty też. */
    private final LocalDate today = LocalDate.now(StoreTime.ZONE);

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
                values (?::uuid, 'BAZA.xlsx', now(), 1, 1, 0, 0, 0) returning id""", String.class, store);
        salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', now(), ?, ?, 1, 1, 0, 0) returning id""",
                String.class, store, today.minusDays(60), today.minusDays(1));
        documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 2, 2, 2, 0, 0) returning id""", String.class, store);

        product(NEVER_DELIVERED, "01 01 01", "-5", "2");
        sale(today.minusDays(1), NEVER_DELIVERED, "3");
        sale(today.minusDays(40), NEVER_DELIVERED, "4");
        product(OLD_DELIVERY, "01 01 01", "-3", "1");
        delivery("P1", today.minusDays(30), OLD_DELIVERY, null, "20");
        product(RECENT_DELIVERY, "01 01 01", "-2", "1");
        delivery("P2", today.minusDays(3), RECENT_DELIVERY, null, "20");
        product(LOCAL_DELIVERY, "01 01 01", "-4", "1");
        delivery("P3", today.minusDays(5), LOCAL_ITM8, "5900000000804", "6");
        product(POSITIVE, "01 01 01", "5", "1");
        product(PRODUCTION, "36 01 01", "-100", "1");
        product(BAG, "60 01 01", "-1", "1");
    }

    @Test
    void defaultShowsNegativeStockWithoutDeliveryFor14Days() throws Exception {
        mvc.perform(report())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("NO_DELIVERY_SINCE"))
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(NEVER_DELIVERED, OLD_DELIVERY)))
                .andExpect(jsonPath("$.items[0].itm8").value(NEVER_DELIVERED))
                .andExpect(jsonPath("$.items[0].stockValue").value(-10.0))
                .andExpect(jsonPath("$.items[0].soldQuantity").value(3.0))
                .andExpect(jsonPath("$.items[0].lastSale").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].deliveryDays").value(0))
                .andExpect(jsonPath("$.items[1].lastDeliveryDate").value(today.minusDays(30).toString()))
                .andExpect(jsonPath("$.items[1].daysSinceDelivery").value(30))
                .andExpect(jsonPath("$.items[1].supplierCode").value("7"));
    }

    @Test
    void deliveryConditionIsAdjustable() throws Exception {
        mvc.perform(report().param("days", "2")).andExpect(jsonPath("$.items[*].itm8",
                containsInAnyOrder(NEVER_DELIVERED, OLD_DELIVERY, RECENT_DELIVERY, LOCAL_DELIVERY)));
        mvc.perform(report().param("delivery", "NEVER"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(NEVER_DELIVERED)));
        // Dostawa pod lokalnym ITM8 dostawcy należy do artykułu po EAN-ie.
        mvc.perform(report().param("delivery", "DELIVERED_IN").param("from", today.minusDays(7).toString())
                        .param("to", today.toString()))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(RECENT_DELIVERY, LOCAL_DELIVERY)))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + LOCAL_DELIVERY + "')].periodDeliveries").value(1));
        mvc.perform(report().param("delivery", "ANY")).andExpect(jsonPath("$.items[*].itm8",
                containsInAnyOrder(NEVER_DELIVERED, OLD_DELIVERY, RECENT_DELIVERY, LOCAL_DELIVERY)));
    }

    @Test
    void productionOnlyOnRequestAndBagsNever() throws Exception {
        mvc.perform(report().param("delivery", "ANY").param("withProduction", "true"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(NEVER_DELIVERED, OLD_DELIVERY,
                        RECENT_DELIVERY, LOCAL_DELIVERY, PRODUCTION)));
        mvc.perform(report().param("nomenclatures", "36"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(PRODUCTION)));
    }

    @Test
    void suggestsSimilarArticleOnPlusWithStockCloseToMissingQuantity() throws Exception {
        // -5 i +5 w tej samej podrodzinie, nazwy podobne; -3 i -4 nie mieszczą się w ±20% od 5.
        mvc.perform(report().param("delivery", "ANY"))
                .andExpect(jsonPath("$.pairTolerance").value(20))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + NEVER_DELIVERED + "')].counterparts[0].itm8")
                        .value(POSITIVE))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + NEVER_DELIVERED + "')].counterparts[0].stock")
                        .value(5.0))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + NEVER_DELIVERED + "')].counterparts[0].sameSubfamily")
                        .value(true))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + LOCAL_DELIVERY + "')].counterparts.length()")
                        .value(0));
        mvc.perform(report().param("delivery", "ANY").param("pairTolerance", "30"))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + LOCAL_DELIVERY + "')].counterparts[0].itm8")
                        .value(POSITIVE))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + OLD_DELIVERY + "')].counterparts.length()")
                        .value(0));
    }

    @Test
    void rejectsWrongParameters() throws Exception {
        mvc.perform(report().param("pairTolerance", "0")).andExpect(status().isBadRequest());
        mvc.perform(report().param("days", "0")).andExpect(status().isBadRequest());
        mvc.perform(report().param("delivery", "DELIVERED_IN")).andExpect(status().isBadRequest());
        mvc.perform(report().param("delivery", "DELIVERED_IN").param("from", today.toString())
                .param("to", today.minusDays(1).toString())).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder report() {
        return get("/api/stores/" + store + "/reports/negative-stock").cookie(owner);
    }

    private void product(String itm8, String nomenclature, String stock, String purchasePrice) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, stock, purchase_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, ?::numeric, ?::numeric, true, ?::uuid, ?::uuid)""",
                store, itm8, "590000000" + itm8.substring(9), "ARTYKUL " + itm8, nomenclature, stock, purchasePrice,
                productImport, productImport);
    }

    private void sale(LocalDate day, String itm8, String quantity) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?, ?, ?, ?::numeric, 0, ?::numeric * 5, 0, 0, ?::uuid)""",
                store, day, itm8, itm8, quantity, quantity, salesImport);
    }

    private void delivery(String number, LocalDate day, String itm8, String ean, String quantity) {
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?::timestamp, 1, 10, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, number, day.toString(), documentImport, documentImport);
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
