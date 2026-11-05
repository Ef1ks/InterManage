package com.intermanage.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

/** Ustawianie promocji: cena zwykła mimo trwającej promocji i ostatnia cena zakupu z przyjęć PZ. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PromotionSetupIntegrationTests {

    private static final String ITM8 = "0000000000001";
    private static final String EAN = "5900000000001";

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
        productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'baza.xlsx', 1, 1, 0, 0, 0) returning id""",
                String.class, store);
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, brand, unit, net_content, purchase_price, sale_price,
                    vat_code, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, 'KAWA 250G', 'MARKA', 'szt.', 1, 9.00, 19.99, 1, true, ?::uuid, ?::uuid)""",
                store, ITM8, EAN, productImport, productImport);
    }

    @Test
    void usesCatalogWithoutDeliveries() throws Exception {
        articles(EAN)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value(EAN))
                .andExpect(jsonPath("$[0].itm8").value(ITM8))
                .andExpect(jsonPath("$[0].ean").value(EAN))
                .andExpect(jsonPath("$[0].vatPercent").value(23))
                .andExpect(jsonPath("$[0].regularPrice").value(19.99))
                .andExpect(jsonPath("$[0].lastPurchasePrice").value(9.0))
                .andExpect(jsonPath("$[0].lastPurchaseDate").value(nullValue()));
    }

    @Test
    void findsArticleByItm8AndSkipsUnknownCodes() throws Exception {
        mvc.perform(get("/api/stores/" + store + "/promotion-setup/articles?code=1&code=5909999999999").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code").value("1"))
                .andExpect(jsonPath("$[0].ean").value(EAN));
    }

    @Test
    void takesPriceFromLastDeliveryWithValue() throws Exception {
        delivery("100", "2026-10-01", "20", "160.00");
        delivery("200", "2026-10-10", "10", "95.00");
        // Gratis: wartość 0 nie jest ceną zakupu.
        delivery("300", "2026-10-20", "2", "0");

        articles(EAN)
                .andExpect(jsonPath("$[0].lastPurchasePrice").value(9.5))
                .andExpect(jsonPath("$[0].lastPurchaseDate").value("2026-10-10"));
    }

    @Test
    void regularPriceIgnoresPromotionFromList() throws Exception {
        LocalDate today = LocalDate.now();
        jdbc.update("update products set sale_price = 12.99 where store_id = ?::uuid", store);
        String importId = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, report_generated_at, end_from, end_to, total_rows)
                values (?::uuid, 'Taryfy.pdf', now(), ?, ?, 1) returning id""", String.class, store, today,
                today.plusDays(30));
        jdbc.update("""
                insert into promotions (store_id, itm8, name, start_date, end_date, regular_sale_price, promo_price,
                    margin_percent, first_import_id, last_import_id)
                values (?::uuid, ?, 'KAWA', ?, ?, 19.99, 12.99, 10, ?::uuid, ?::uuid)""",
                store, ITM8, today.minusDays(1), today.plusDays(5), importId, importId);

        articles(EAN).andExpect(jsonPath("$[0].regularPrice").value(19.99));
    }

    @Test
    void regularPriceIgnoresPromotionFromTill() throws Exception {
        LocalDate today = LocalDate.now();
        jdbc.update("update products set sale_price = 12.99 where store_id = ?::uuid", store);
        jdbc.update("""
                insert into till_promotions (store_id, itm8, promo_from, promo_to, promo_price, regular_price, file_time)
                values (?::uuid, ?, ?, ?, 12.99, 18.99, now())""", store, ITM8, today.minusDays(1), today.plusDays(5));

        articles(EAN).andExpect(jsonPath("$[0].regularPrice").value(18.99));
    }

    private org.springframework.test.web.servlet.ResultActions articles(String code) throws Exception {
        return mvc.perform(get("/api/stores/" + store + "/promotion-setup/articles?code=" + code).cookie(owner))
                .andExpect(status().isOk());
    }

    private void delivery(String number, String date, String quantity, String value) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'dok.xlsx', 1, 1, 1, 0, 0) returning id""",
                String.class, store);
        String document = jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, 'PZ', ?, ?::timestamp, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, number, date, importId, importId);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, name, quantity, purchase_value)
                values (?::uuid, 1, ?, ?, 'KAWA 250G', ?::numeric, ?::numeric)""",
                document, ITM8, EAN, quantity, value);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private String createStore() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String response = mvc.perform(post("/api/settings/stores").with(csrf()).cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
