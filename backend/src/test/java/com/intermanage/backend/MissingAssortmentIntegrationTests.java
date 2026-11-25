package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import jakarta.servlet.http.Cookie;

/** Brakujący asortyment: zakupy spoza bazy bez stanu i sprzedaży, sprzedaż na stanie i kategorie sklepów. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class MissingAssortmentIntegrationTests {

    private static final String MISSING = "0000000000801";
    private static final String STILL_SELLING = "0000000000802";
    private static final String IN_STOCK = "0000000000803";
    private static final String BASE_ONLY = "0000000000804";
    private static final String OLD_PURCHASE = "0000000000805";
    private static final String NEVER_SOLD = "0000000000806";

    /** Ostatni dzień sprzedaży; okres zakupów liczy się od dziś, więc daty idą od dzisiejszej. */
    private final LocalDate last = LocalDate.now(ZoneId.of("Europe/Warsaw")).minusDays(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private Cookie owner;
    private String store;
    private String partner;
    private String productImport;
    private String salesImport;
    private String documentImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        partner = createStore();
        jdbc.update("""
                insert into store_partners (store_id, partner_store_id, accepted_at)
                values (?::uuid, ?::uuid, now())""", store, partner);
        productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, report_generated_at, total_rows, created_count,
                    updated_count, unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', now(), 1, 1, 0, 0, 0) returning id""", String.class, store);
        salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', now(), ?, ?, 1, 1, 0, 0) returning id""",
                String.class, store, last.minusDays(200), last);
        documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 2, 2, 2, 0, 0) returning id""", String.class, store);

        // Bazowa dostawa 80 dni temu: 10 dni po 2 szt.; od dostawcy 7 20 dni temu: 5 dni po 1 szt. i koniec.
        product(MISSING, "0");
        delivery("B1", last.minusDays(80), MISSING, "538", "20", "40");
        for (int i = 0; i < 10; i++) {
            sale(last.minusDays(80 - i), MISSING, "2");
        }
        delivery("P1", last.minusDays(20), MISSING, "7", "10", "25");
        for (int i = 0; i < 5; i++) {
            sale(last.minusDays(20 - i), MISSING, "1");
        }

        product(STILL_SELLING, "0");
        delivery("P2", last.minusDays(20), STILL_SELLING, "7", "10", "10");
        sale(last, STILL_SELLING, "1");

        product(IN_STOCK, "5");
        delivery("P3", last.minusDays(20), IN_STOCK, "7", "10", "10");

        product(BASE_ONLY, "0");
        delivery("P4", last.minusDays(20), BASE_ONLY, "538", "10", "10");

        product(OLD_PURCHASE, "-2");
        delivery("P5", last.minusDays(150), OLD_PURCHASE, "7", "10", "10");

        product(NEVER_SOLD, "0");
        delivery("P6", last.minusDays(10), NEVER_SOLD, "007", "4", "8");
    }

    @Test
    void listsArticlesBoughtOutsideBaseWithoutStockAndSales() throws Exception {
        mvc.perform(report())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSaleDate").value(last.toString()))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(MISSING, NEVER_SOLD)));
        mvc.perform(report().param("months", "6"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(MISSING, NEVER_SOLD, OLD_PURCHASE)));
        mvc.perform(report().param("silentDays", "0"))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(MISSING, NEVER_SOLD, STILL_SELLING)));
        mvc.perform(report().param("months", "13")).andExpect(status().isBadRequest());
    }

    @Test
    void listsArticlesWithLowStockRegardlessOfSales() throws Exception {
        mvc.perform(report().param("maxStock", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.silentDays").value(nullValue()))
                .andExpect(jsonPath("$.items[*].itm8", containsInAnyOrder(MISSING, NEVER_SOLD, STILL_SELLING)));
        mvc.perform(report().param("maxStock", "5"))
                .andExpect(jsonPath("$.items[*].itm8",
                        containsInAnyOrder(MISSING, NEVER_SOLD, STILL_SELLING, IN_STOCK)));
        mvc.perform(report().param("maxStock", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void previewsSuppliersOfManyArticlesForBasket() throws Exception {
        delivery("P7", last.minusDays(5), NEVER_SOLD, "7", "6", "15");
        // Korekta przypięta do ostatniej dostawy obniża jej wartość z 15 do 12 zł.
        String correction = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'WZ', 'K1', now(), 1, -3, 0, ?::uuid, ?::uuid) returning id""", String.class, store,
                documentImport, documentImport);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, quantity, purchase_value, supplier_code)
                values (?::uuid, 1, ?, 0, -3, '7')""", correction, NEVER_SOLD);
        jdbc.update("""
                insert into delivery_corrections (id, delivery_id, correction_id, linked_at)
                select gen_random_uuid(), id, ?::uuid, now() from documents where store_id = ?::uuid and number = 'P7'""", correction, store);

        mvc.perform(post("/api/stores/" + store + "/reports/basket/preview").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itm8s\":[\"" + NEVER_SOLD + "\",\"" + MISSING + "\",\"0000000009999\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].itm8", contains(NEVER_SOLD, MISSING)))
                .andExpect(jsonPath("$[0].cadencier").value(false))
                .andExpect(jsonPath("$[0].suppliers", hasSize(1)))
                .andExpect(jsonPath("$[0].suppliers[0].code").value("7"))
                .andExpect(jsonPath("$[0].suppliers[0].purchasePrice").value(2.0))
                .andExpect(jsonPath("$[0].suppliers[0].lastQuantity").value(6.0))
                .andExpect(jsonPath("$[0].suppliers[0].deliveries").value(2))
                .andExpect(jsonPath("$[1].suppliers[*].code", contains("7", "538")))
                .andExpect(jsonPath("$[1].suppliers[*].rank", contains(1, 2)));
        mvc.perform(post("/api/stores/" + store + "/reports/basket/preview").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itm8s\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void averagesSalesOnlyFromDaysInStock() throws Exception {
        // 25 szt. w 15 dniach na stanie, czyli 11,67 tygodniowo; ostatni zakup od dostawcy 7 po 2,50 zł.
        String item = "$.items[?(@.itm8 == '" + MISSING + "')]";
        mvc.perform(report())
                .andExpect(jsonPath(item + ".daysInStock").value(15))
                .andExpect(jsonPath(item + ".soldInStock").value(25.0))
                .andExpect(jsonPath(item + ".weeklyInStock").value(11.67))
                .andExpect(jsonPath(item + ".supplierCode").value("7"))
                .andExpect(jsonPath(item + ".lastPurchasePrice").value(2.5))
                .andExpect(jsonPath(item + ".silentDays").value(16))
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + NEVER_SOLD + "')].weeklyInStock",
                        containsInAnyOrder(nullValue())));
    }

    @Test
    void categoriesAreSharedWithPartnersAndAssignedPerStore() throws Exception {
        String json = mvc.perform(post(categories(store)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Wycofane z produkcji\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String category = JsonPath.read(json, "$[0].id");

        mvc.perform(get(categories(partner)).cookie(owner))
                .andExpect(jsonPath("$[0].id").value(category))
                .andExpect(jsonPath("$[0].own").value(false));
        mvc.perform(put(categories(partner) + "/" + category).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Inna\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(assign(store, "{\"items\":[{\"itm8\":\"" + MISSING + "\",\"categoryId\":\"" + category + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].articleCount").value(1));
        mvc.perform(report())
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].categoryId").value(category));
        // W sklepie zaprzyjaźnionym przypisanie jest osobne.
        mvc.perform(get(categories(partner)).cookie(owner)).andExpect(jsonPath("$[0].articleCount").value(0));

        // Usunięta kategoria oddaje artykuł do „Do sprawdzenia”, przywrócona znów go ma.
        mvc.perform(delete(categories(store) + "/" + category).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(report())
                .andExpect(jsonPath("$.items[?(@.itm8 == '" + MISSING + "')].categoryId",
                        containsInAnyOrder(nullValue())));
        mvc.perform(post(categories(store) + "/" + category + "/restore").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(assign(store, "{\"items\":[{\"itm8\":\"" + MISSING + "\",\"categoryId\":null}]}"))
                .andExpect(jsonPath("$[0].articleCount").value(0));
        Integer history = jdbc.queryForObject(
                "select count(*) from missing_assortment_assignments where store_id = ?::uuid", Integer.class, store);
        assertThat(history).isEqualTo(1);

        mvc.perform(assign(store, "{\"items\":[{\"itm8\":\"" + MISSING + "\",\"categoryId\":\"" + UUID.randomUUID()
                        + "\"}]}"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder report() {
        return get("/api/stores/" + store + "/reports/missing-assortment").cookie(owner);
    }

    private static String categories(String storeId) {
        return "/api/stores/" + storeId + "/reports/missing-assortment/categories";
    }

    private MockHttpServletRequestBuilder assign(String storeId, String body) {
        return put("/api/stores/" + storeId + "/reports/missing-assortment/assignments").cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void product(String itm8, String stock) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, nomenclature, stock, purchase_price, sale_price,
                    active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, ?, '01 01 01', ?::numeric, 1, 4, true, ?::uuid, ?::uuid)""",
                store, itm8, "590000000" + itm8.substring(9), "ARTYKUL " + itm8, stock, productImport,
                productImport);
    }

    private void sale(LocalDate day, String itm8, String quantity) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                values (?::uuid, ?, ?, ?, ?::numeric, 0, 0, 0, 0, ?::uuid)""",
                store, day, itm8, itm8, quantity, salesImport);
    }

    private void delivery(String number, LocalDate day, String itm8, String supplier, String quantity,
            String value) {
        String documentId = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?::timestamp, 1, ?::numeric, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, number, day.toString(), value, documentImport, documentImport);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, quantity, purchase_value,
                    supplier_code, supplier_name)
                values (?::uuid, 1, ?, null, ?::numeric, ?::numeric, ?, 'HURTOWNIA')""",
                UUID.fromString(documentId), itm8, quantity, value, supplier);
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
