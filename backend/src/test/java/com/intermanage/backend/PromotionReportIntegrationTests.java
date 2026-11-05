package com.intermanage.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * Skuteczność promocji: promocje rozpoznane ze spadku ceny w sprzedaży i z listy promocji, baza z 28 dni przed,
 * próg opłacalności i werdykty. Każdy artykuł zwykle sprzedaje 10 szt. dziennie po 10 zł przy koszcie 6 zł
 * (marża 4 zł na sztuce, 40 zł dziennie); VAT 0, więc netto = brutto.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PromotionReportIntegrationTests {

    private static final String PROFITABLE = "0000000000001";
    private static final String NO_REACTION = "0000000000002";
    private static final String BELOW_COST = "0000000000003";
    private static final String LISTED = "0000000000004";
    private static final String REGULAR = "0000000000005";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SaleRepository sales;

    @Autowired
    TransactionTemplate transaction;

    private Cookie owner;
    private String store;
    private String salesImport;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        salesImport = jdbc.queryForObject("""
                insert into sales_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_rows, created_count, existing_count, conflict_count)
                values (?::uuid, 'sprzedaz.xlsx', timestamp '2026-11-01 07:00:00', date '2026-07-01',
                    date '2026-10-31', 1, 1, 0, 0) returning id""", String.class, store);
    }

    @Test
    void leavesOutArticlesInLeaflets() throws Exception {
        article(PROFITABLE, "2026-10-05", "2026-10-11", 40, "8");
        article(NO_REACTION, "2026-10-05", "2026-10-11", 11, "8");
        article(BELOW_COST, "2026-10-05", "2026-10-11", 30, "5");
        // Promocja z gazetki po ITM 8 i po EAN-ie z multikodów: nie są promocjami sklepu.
        leaflet(41, "2", "5900000000002", "2026-10-08", "2026-10-14");
        String productCodeImport = jdbc.queryForObject("""
                insert into product_code_imports (store_id, file_name, report_generated_at, total_rows)
                values (?::uuid, 'Multikody.xlsx', timestamp '2026-10-01 06:00:00', 1) returning id""",
                String.class, store);
        jdbc.update("""
                insert into product_codes (store_id, itm8, ean, prio, first_import_id, last_import_id)
                values (?::uuid, ?, '5900000000003', true, ?::uuid, ?::uuid)""",
                store, BELOW_COST, productCodeImport, productCodeImport);
        leaflet(42, null, "5900000000003", "2026-10-05", "2026-10-11");
        // Gazetka przed promocją: jej dni nie są zwykłą sprzedażą w bazie (28 dni - 7).
        leaflet(39, "1", "5900000000001", "2026-09-20", "2026-09-26");

        mvc.perform(get("/api/stores/" + store + "/reports/promotions?from=2026-10-01&to=2026-10-31").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafletsSince").value("2026-09-20"))
                .andExpect(jsonPath("$.promotions", hasSize(1)))
                .andExpect(jsonPath("$.promotions[0].itm8").value(PROFITABLE))
                .andExpect(jsonPath("$.promotions[0].baseDays").value(21));
    }

    @Test
    void comparesPromotionsWithSalesBeforeThem() throws Exception {
        // 7 dni po 8 zł (marża 2 zł na sztuce): próg opłacalności 2x.
        article(PROFITABLE, "2026-10-05", "2026-10-11", 40, "8");
        article(NO_REACTION, "2026-10-05", "2026-10-11", 11, "8");
        article(BELOW_COST, "2026-10-05", "2026-10-11", 30, "5");
        article(LISTED, "2026-10-12", "2026-10-18", 25, "8");
        article(REGULAR, null, null, 0, null);
        String importId = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, report_generated_at, end_from, end_to, total_rows)
                values (?::uuid, 'Taryfy.pdf', timestamp '2026-10-10 06:00:00', date '2026-10-10',
                    date '2026-12-31', 1) returning id""", String.class, store);
        jdbc.update("""
                insert into promotions (store_id, itm8, name, start_date, end_date, regular_sale_price, promo_price,
                    margin_percent, first_import_id, last_import_id)
                values (?::uuid, ?, 'ARTYKUL Z LISTY', date '2026-10-12', date '2026-10-18', 10, 8, 25,
                    ?::uuid, ?::uuid)""", store, LISTED, importId, importId);

        mvc.perform(get("/api/stores/" + store + "/reports/promotions?from=2026-10-01&to=2026-10-31").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSaleDate").value("2026-10-31"))
                .andExpect(jsonPath("$.promotions", hasSize(4)))
                // Od najgorszej dodatkowej marży: poniżej kosztu, brak reakcji, potem opłacalne.
                .andExpect(jsonPath("$.promotions[0].itm8").value(BELOW_COST))
                .andExpect(jsonPath("$.promotions[0].verdict").value("BELOW_COST"))
                .andExpect(jsonPath("$.promotions[0].breakEvenUplift").value(nullValue()))
                .andExpect(jsonPath("$.promotions[1].itm8").value(NO_REACTION))
                .andExpect(jsonPath("$.promotions[1].verdict").value("NO_REACTION"))
                .andExpect(jsonPath("$.promotions[1].uplift").value(1.1))
                .andExpect(jsonPath("$.promotions[1].breakEvenUplift").value(2.0))
                // 11 szt. x 2 zł - 40 zł dziennie, przez 7 dni.
                .andExpect(jsonPath("$.promotions[1].extraMargin").value(-126.0))
                .andExpect(jsonPath("$.promotions[2].itm8").value(LISTED))
                .andExpect(jsonPath("$.promotions[2].listed").value(true))
                .andExpect(jsonPath("$.promotions[2].startDate").value("2026-10-12"))
                .andExpect(jsonPath("$.promotions[2].listMarginPercent").value(25.0))
                .andExpect(jsonPath("$.promotions[2].verdict").value("PROFITABLE"))
                .andExpect(jsonPath("$.promotions[2].extraMargin").value(70.0))
                .andExpect(jsonPath("$.promotions[3].itm8").value(PROFITABLE))
                .andExpect(jsonPath("$.promotions[3].listed").value(false))
                .andExpect(jsonPath("$.promotions[3].startDate").value("2026-10-05"))
                .andExpect(jsonPath("$.promotions[3].endDate").value("2026-10-11"))
                .andExpect(jsonPath("$.promotions[3].regularPrice").value(10.0))
                .andExpect(jsonPath("$.promotions[3].promoPrice").value(8.0))
                .andExpect(jsonPath("$.promotions[3].discountPercent").value(20.0))
                .andExpect(jsonPath("$.promotions[3].baseDays").value(28))
                .andExpect(jsonPath("$.promotions[3].uplift").value(4.0))
                .andExpect(jsonPath("$.promotions[3].breakEvenUplift").value(2.0))
                .andExpect(jsonPath("$.promotions[3].extraMargin").value(280.0))
                .andExpect(jsonPath("$.promotions[3].afterRatio").value(1.0))
                .andExpect(jsonPath("$.promotions[3].verdict").value("PROFITABLE"));

        mvc.perform(get("/api/stores/" + store + "/reports/promotions?from=2026-07-01&to=2026-10-31").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    /** Sprzedaż artykułu od lipca do końca października: zwykle 10 szt. po 10 zł, w promocji podana ilość i cena. */
    /** Pozycja gazetki z ITM 8 bez zer wiodących, jak w pliku z centrali. */
    private void leaflet(int week, String itm8, String ean, String from, String to) {
        String leaflet = jdbc.queryForObject("""
                insert into promo_leaflets (store_id, year, week, file_name, line_count)
                values (?::uuid, 2026, ?, 'gazetka.xlsx', 1) returning id""", String.class, store, week);
        jdbc.update("""
                insert into promo_leaflet_lines (leaflet_id, line_no, date_from, date_to, itm8, ean, name)
                values (?::uuid, 1, ?::date, ?::date, ?, ?, 'Z GAZETKI')""", leaflet, from, to, itm8, ean);
    }

    private void article(String itm8, String promoFrom, String promoTo, int promoQuantity, String promoPrice) {
        jdbc.update("""
                insert into sales (store_id, sale_date, itm8, ean, quantity, cost_value, sale_value_gross, vat_value,
                    margin_value, import_id)
                select ?::uuid, d, ?, null, q.quantity, q.quantity * 6, q.quantity * q.price, 0,
                       q.quantity * (q.price - 6), ?::uuid
                from generate_series(date '2026-07-01', date '2026-10-31', interval '1 day') d
                cross join lateral (select case when d between ?::date and ?::date then ? else 10 end as quantity,
                                           case when d between ?::date and ?::date then ?::numeric else 10 end as price) q
                """, store, itm8, salesImport, promoFrom, promoTo, promoQuantity, promoFrom, promoTo, promoPrice);
        transaction.executeWithoutResult(status -> sales.refreshMonthPrices(UUID.fromString(store),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-10-31")));
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
