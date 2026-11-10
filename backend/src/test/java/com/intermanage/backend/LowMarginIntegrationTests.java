package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/**
 * Za niskie marże: marża wymagana na rejonie 57 to 30%, a artykuł bez VAT-u kupiony za 10 zł ma z ceny 12,50 zł marżę
 * 20%, czyli dokładnie o próg niższą.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class LowMarginIntegrationTests {

    private static final String ON_THRESHOLD = "0000000000301";
    private static final String ABOVE_THRESHOLD = "0000000000302";
    private static final String CORRECTED = "0000000000303";
    private static final String GRATIS = "0000000000304";
    private static final String OLD = "0000000000305";
    private static final String LISTED = "0000000000306";
    private static final String LISTED_LOW = "0000000000307";
    private static final String RECOGNIZED = "0000000000308";
    private static final String SMALL_DROP = "0000000000309";
    private static final String LEAFLET = "0000000000310";
    private static final String OTHER_REGION = "0000000000311";
    private static final String NO_MARGIN = "0000000000312";
    private static final String LOCAL_ITM8 = "9900000000313";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    private Cookie owner;
    private String store;
    private String documentImport;
    private int nextNumber = 100000;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        store = createStore();
        documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', ?::timestamp, ?::date, ?::date, 1, 1, 1, 0, 0) returning id""",
                String.class, store, today + " 08:00", today.minusDays(200), today);
        createNomenclature();
        // Większość przypadków jest liczona z progiem 10 punktów w dół; domyślne progi sprawdza osobny test.
        gaps("10", "10");
    }

    @Test
    void reportsMarginsAtLeastTenPointsBelowRequired() throws Exception {
        requiredMargin("57", "30");
        String flagged = delivery(ON_THRESHOLD, 5, "10", "100.00", "12.50", "570101");
        delivery(ABOVE_THRESHOLD, 5, "10", "100.00", "12.51", "570101");

        // Korekta obniżyła zakup do 8 zł: marża z 12,50 zł to 36%, bez korekty byłoby 20%.
        String corrected = delivery(CORRECTED, 5, "10", "100.00", "12.50", "570101");
        correction(corrected, CORRECTED, "0", "-20.00");

        // Gratis nie ma ceny zakupu, a przyjęcie sprzed 30 dni jest poza okresem.
        delivery(GRATIS, 5, "10", "0", "12.50", "570101");
        delivery(OLD, 40, "10", "100.00", "12.50", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginsConfigured").value(true))
                .andExpect(jsonPath("$.lowGap").value(10.0))
                .andExpect(jsonPath("$.highGap").value(10.0))
                .andExpect(jsonPath("$.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.deliveries[0].id").value(flagged))
                .andExpect(jsonPath("$.deliveries[0].linesPurchaseValue").value(100.0))
                // Cena dająca 30% to 8,75 zł, więc 10 szt. kupiono za drogo o 12,50 zł.
                .andExpect(jsonPath("$.deliveries[0].excessValue").value(12.5))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(ON_THRESHOLD))
                .andExpect(jsonPath("$.deliveries[0].lines[0].marginPercent").value(20.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].requiredMargin").value(30.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].targetPurchasePrice").value(8.75))
                .andExpect(jsonPath("$.deliveries[0].lines[0].promotion").isEmpty())
                .andExpect(jsonPath("$.deliveries[0].lines[0].deviation").value("LOW"));
    }

    @Test
    void defaultGapsFlagMarginsFivePointsBelowAndTenAboveRequired() throws Exception {
        jdbc.update("delete from low_margin_settings where store_id = ?::uuid", store);
        requiredMargin("57", "30");
        // Zakup 10 zł: 13,33 zł daje 25% (5 punktów niżej), 13,34 zł już 25,04%.
        delivery(ON_THRESHOLD, 5, "10", "100.00", "13.33", "570101");
        delivery(ABOVE_THRESHOLD, 5, "10", "100.00", "13.34", "570101");
        // 16,67 zł daje 40,01% (10 punktów wyżej), 16,66 zł 39,98%.
        delivery(LISTED, 5, "10", "100.00", "16.67", "570101");
        delivery(LISTED_LOW, 5, "10", "100.00", "16.66", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.lowGap").value(5.0))
                .andExpect(jsonPath("$.highGap").value(10.0))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD, LISTED)))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED
                        + "')].lines[0].deviation").value("HIGH"))
                // Nadwyżka: cena dająca 30% to 11,67 zł, więc 10 szt. kupiono taniej o 16,69 zł.
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED
                        + "')].lines[0].excessValue").value(16.69))
                // Kwota przyjęcia to tylko brakująca marża z za niskich.
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED
                        + "')].excessValue").value(0));
    }

    @Test
    void storeSetsOwnGapsAndLongerPeriod() throws Exception {
        requiredMargin("57", "30");
        delivery(ON_THRESHOLD, 5, "10", "100.00", "13.33", "570101");
        delivery(OLD, 60, "10", "100.00", "12.50", "570101");

        mvc.perform(put(url() + "/settings?days=90").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowGap\":5,\"highGap\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowGap").value(5.0))
                .andExpect(jsonPath("$.highGap").value(15.0))
                .andExpect(jsonPath("$.periodDays").value(90))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD, OLD)));
        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD)));
        mvc.perform(get(url() + "?days=366").cookie(owner)).andExpect(status().isBadRequest());
        mvc.perform(put(url() + "/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowGap\":-1,\"highGap\":15}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ignoresPromotionalPriceAndUsesCatalogPrice() throws Exception {
        requiredMargin("57", "30");
        String promotions = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, total_rows) values (?::uuid, 'Taryfy.pdf', 1)
                returning id""", String.class, store);

        // W promocji 11 zł dawałoby 9% marży, ale zwykła cena 15 zł to 33%: bez zastrzeżeń.
        promotion(promotions, LISTED, "15.00", "11.00");
        delivery(LISTED, 5, "10", "100.00", "11.00", "570101");
        // Zwykła cena też jest za niska: raport pokazuje marżę z 12 zł, a nie z 11 zł.
        promotion(promotions, LISTED_LOW, "12.00", "11.00");
        delivery(LISTED_LOW, 5, "10", "100.00", "11.00", "570101");

        // Promocji nie ma na liście, ale cena 11 zł to mniej niż 85% zwykłej ze sprzedaży.
        monthPrice(RECOGNIZED, 15);
        delivery(RECOGNIZED, 5, "10", "100.00", "11.00", "570101");
        // Spadek do 90% ceny zwykłej to nie promocja: liczy się cena z dokumentu.
        monthPrice(SMALL_DROP, 12.2);
        delivery(SMALL_DROP, 5, "10", "100.00", "11.00", "570101");

        // Gazetka bez listy promocji: cena zwykła z kartoteki.
        leaflet(LEAFLET);
        product(LEAFLET, "15.00");
        delivery(LEAFLET, 5, "10", "100.00", "11.00", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(LISTED_LOW, SMALL_DROP)))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].salePrice").value(12.0))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].documentSalePrice").value(11.0))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].promotion").value("LIST"))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].marginPercent").value(16.67));
    }

    @Test
    void usesRegularPriceFromTillFilesForManualPromotions() throws Exception {
        requiredMargin("57", "30");
        // Promocji założonej ręcznie nie ma na liście ani w gazetce, a cena 11 zł to ponad 85% ceny ze sprzedaży.
        tillPromotion(LISTED, "15.00", "11.00");
        monthPrice(LISTED, 12.5);
        delivery(LISTED, 5, "10", "100.00", "11.00", "570101");
        // Zwykła cena z kasy też jest za niska.
        tillPromotion(LISTED_LOW, "12.00", "11.00");
        delivery(LISTED_LOW, 5, "10", "100.00", "11.00", "570101");
        // Promocja skończyła się przed przyjęciem, więc liczy się cena z dokumentu.
        jdbc.update("""
                insert into till_promotions (store_id, itm8, promo_from, promo_to, promo_price, regular_price, file_time)
                values (?::uuid, ?, ?::date, ?::date, 11.00, 15.00, now())""",
                store, SMALL_DROP, today.minusDays(20), today.minusDays(10));
        delivery(SMALL_DROP, 5, "10", "100.00", "11.00", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(LISTED_LOW, SMALL_DROP)))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].promotion").value("TILL"))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + LISTED_LOW
                        + "')].lines[0].salePrice").value(12.0));
    }

    @Test
    void localItm8TakesPromotionOfCatalogArticleWithTheSameEan() throws Exception {
        requiredMargin("57", "30");
        // Promocja z kasy jest na artykule z kartoteki, a dostawca spoza bazy przywozi go pod własnym ITM8.
        product(LISTED, "15.00");
        tillPromotion(LISTED, "15.00", "11.00");
        delivery(LOCAL_ITM8, 5, "10", "100.00", "11.00", "570101");
        jdbc.update("update document_lines set ean = '05900000000000' where itm8 = ?", LOCAL_ITM8);
        // Bez promocji przy artykule zostaje cena z dokumentu.
        delivery(ON_THRESHOLD, 5, "10", "100.00", "11.00", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD)));
    }

    @Test
    void usesCurrentTillPriceInsteadOfPriceFromDelivery() throws Exception {
        requiredMargin("57", "30");
        // Cenę podniesiono po przyjęciu z 11 zł na 15 zł: marża liczy się z nowej, więc bez zastrzeżeń.
        product(LISTED, "15.00");
        delivery(LISTED, 5, "10", "100.00", "11.00", "570101");
        // Cenę obniżono z 15 zł na 11 zł: na PZ marża była dobra, teraz jest za niska.
        product(LISTED_LOW, "11.00");
        delivery(LISTED_LOW, 5, "10", "100.00", "15.00", "570101");
        // Promocja rozpoczęta przed raportem kartoteki zostawia w kartotece cenę promocyjną: liczy się cena zwykła.
        String promotions = jdbc.queryForObject("""
                insert into promotion_imports (store_id, file_name, total_rows) values (?::uuid, 'Taryfy.pdf', 1)
                returning id""", String.class, store);
        product(RECOGNIZED, "11.00");
        promotion(promotions, RECOGNIZED, "15.00", "11.00");
        delivery(RECOGNIZED, 5, "10", "100.00", "15.00", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(LISTED_LOW)))
                .andExpect(jsonPath("$.deliveries[0].lines[0].salePrice").value(11.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].documentSalePrice").value(15.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].promotion").isEmpty());
    }

    @Test
    void suppliersWithoutRequiredMarginAreLeftOutUntilRemovedFromTheList() throws Exception {
        requiredMargin("57", "30");
        jdbc.update("insert into suppliers (store_id, code, name) values (?::uuid, '100', 'Dostawca 100')", store);
        delivery(ON_THRESHOLD, 5, "10", "100.00", "12.50", "570101");

        mvc.perform(put(url() + "/excluded-suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[\"100\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedSuppliers[0].code").value("100"))
                .andExpect(jsonPath("$.excludedSuppliers[0].name").value("Dostawca 100"))
                .andExpect(jsonPath("$.deliveries", hasSize(0)));
        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.suppliers[0].code").value("100"))
                .andExpect(jsonPath("$.deliveries", hasSize(0)));

        mvc.perform(put(url() + "/excluded-suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[]}"))
                .andExpect(jsonPath("$.excludedSuppliers", hasSize(0)))
                .andExpect(jsonPath("$.deliveries", hasSize(1)));
        assertThat(jdbc.queryForObject("""
                select count(*) from report_excluded_suppliers
                where store_id = ?::uuid and report = 'LOW_MARGIN' and removed_at is not null""",
                Integer.class, store)).isEqualTo(1);

        mvc.perform(put(url() + "/excluded-suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[\"999\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mostDetailedRequiredMarginWinsAndCountsNetOfVat() throws Exception {
        requiredMargin("57", "30");
        requiredMargin("5701", "10");
        // Rodzina wymaga 10%, więc marża 0% z 12,30 zł brutto przy VAT 23% to dokładnie próg.
        String id = document("PZ", 5);
        line(id, ON_THRESHOLD, "10", "100.00", "12.30", "57 01 01", 23);
        // Rejon bez marży nie jest oceniany, nawet przy stracie.
        delivery(NO_MARGIN, 5, "10", "100.00", "5.00", "580101");
        // Pod tym samym rejonem działa marża rejonu.
        delivery(OTHER_REGION, 5, "10", "100.00", "12.50", "570201");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD, OTHER_REGION)))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + ON_THRESHOLD
                        + "')].lines[0].requiredMargin").value(10.0))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + ON_THRESHOLD
                        + "')].lines[0].marginPercent").value(0.0))
                .andExpect(jsonPath("$.deliveries[?(@.lines[0].itm8 == '" + ON_THRESHOLD
                        + "')].lines[0].regionCode").value("57"));
    }

    @Test
    void reviewedDeliveryStaysAndStatusIsSeparateFromOtherReports() throws Exception {
        requiredMargin("57", "30");
        String old = delivery(OLD, 40, "10", "100.00", "12.50", "570101");

        String json = mvc.perform(post(url() + "/documents/" + old + "/reviews").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REPORTED\",\"note\":\"mail do dostawcy\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reviewId = JsonPath.read(json, "$[0].id");
        assertThat(jdbc.queryForObject("select report from price_check_reviews where id = ?::uuid", String.class,
                reviewId)).isEqualTo("LOW_MARGIN");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.deliveries[0].review.status").value("REPORTED"))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(OLD));
        mvc.perform(get("/api/stores/" + store + "/reports/overpayments").cookie(owner))
                .andExpect(jsonPath("$.deliveries", hasSize(0)));
    }

    @Test
    void skipsKolporter() throws Exception {
        requiredMargin("57", "30");
        jdbc.update("""
                insert into suppliers (store_id, code, name, nip)
                values (?::uuid, '52', 'KOLPORTER SP. Z O.O.', '9591452482')""", store);
        delivery(ON_THRESHOLD, 5, "10", "100.00", "12.50", "570101");
        delivery(ABOVE_THRESHOLD, 5, "10", "100.00", "12.50", "570101");
        jdbc.update("update document_lines set supplier_code = '52' where itm8 = ?", ABOVE_THRESHOLD);

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD)));
    }

    @Test
    void skipsArticlesActiveInCadencier() throws Exception {
        requiredMargin("57", "30");
        delivery(ON_THRESHOLD, 5, "10", "100.00", "12.50", "570101");
        delivery(ABOVE_THRESHOLD, 5, "10", "100.00", "12.50", "570101");
        // Dostawca spoza bazy przywozi ten sam towar pod własnym ITM8, więc artykuł łączy dopiero EAN.
        delivery(LOCAL_ITM8, 5, "10", "100.00", "12.50", "570101");
        jdbc.update("update document_lines set ean = '05900000000017' where itm8 = ?", LOCAL_ITM8);

        String cadencier = jdbc.queryForObject("""
                insert into cadencier_imports (store_id, file_name, total_rows)
                values (?::uuid, 'obecne w cad.xlsx', 2) returning id""", String.class, store);
        jdbc.update("""
                insert into cadencier_items (store_id, itm8, ean, import_id)
                values (?::uuid, ?, null, ?::uuid), (?::uuid, ?, '5900000000017', ?::uuid)""",
                store, ABOVE_THRESHOLD, cadencier, store, OTHER_REGION, cadencier);

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(ON_THRESHOLD)));
    }

    @Test
    void storeWithoutRequiredMarginsHasEmptyReport() throws Exception {
        delivery(ON_THRESHOLD, 5, "10", "100.00", "12.50", "570101");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginsConfigured").value(false))
                .andExpect(jsonPath("$.deliveries", hasSize(0)));
    }

    private void gaps(String low, String high) {
        jdbc.update("insert into low_margin_settings (store_id, low_gap, high_gap) values (?::uuid, ?::numeric, ?::numeric)",
                store, low, high);
    }

    private String url() {
        return "/api/stores/" + store + "/reports/low-margins";
    }

    /** Rejon 57 z dwiema rodzinami i podrodzinami, bo marża obowiązuje tylko na nomenklaturze sklepu. */
    private void createNomenclature() {
        String nomenclatureImport = jdbc.queryForObject("""
                insert into nomenclature_imports (store_id, file_name, total_rows, created_count, updated_count,
                    missing_count)
                values (?::uuid, 'nomenklatury.pdf', 5, 5, 0, 0) returning id""", String.class, store);
        for (String code : List.of("57", "5701", "570101", "5702", "570201")) {
            jdbc.update("""
                    insert into nomenclatures (store_id, code, level, parent_id, name, last_import_id)
                    values (?::uuid, ?, ?, (select id from nomenclatures where store_id = ?::uuid and code = ?), ?,
                        ?::uuid)""", store, code,
                    code.length() == 2 ? "REGION" : code.length() == 4 ? "FAMILY" : "SUBFAMILY", store,
                    code.substring(0, code.length() - 2), "Pozycja " + code, nomenclatureImport);
        }
    }

    private void requiredMargin(String code, String margin) {
        jdbc.update("insert into nomenclature_margins (store_id, code, margin_percent) values (?::uuid, ?, ?::numeric)",
                store, code, margin);
    }

    private void promotion(String importId, String itm8, String regular, String promo) {
        jdbc.update("""
                insert into promotions (store_id, itm8, name, start_date, end_date, regular_sale_price, promo_price,
                    first_import_id, last_import_id)
                values (?::uuid, ?, 'Promocja', ?::date, ?::date, ?::numeric, ?::numeric, ?::uuid, ?::uuid)""",
                store, itm8, today.minusDays(10), today.plusDays(10), regular, promo, importId, importId);
    }

    private void tillPromotion(String itm8, String regular, String promo) {
        jdbc.update("""
                insert into till_promotions (store_id, itm8, promo_from, promo_to, promo_price, regular_price, file_time)
                values (?::uuid, ?, ?::date, ?::date, ?::numeric, ?::numeric, now())""",
                store, itm8, today.minusDays(10), today.plusDays(10), promo, regular);
    }

    /** Cena zwykła ze sprzedaży w miesiącu przyjęcia. */
    private void monthPrice(String itm8, double price) {
        jdbc.update("""
                insert into sales_month_prices (store_id, itm8, month, price, sale_days)
                values (?::uuid, ?, ?::date, ?, 20)""",
                store, itm8, today.minusDays(5).withDayOfMonth(1), price);
    }

    private void leaflet(String itm8) {
        String leaflet = jdbc.queryForObject("""
                insert into promo_leaflets (store_id, year, week, file_name, line_count)
                values (?::uuid, 2026, 44, 'gazetka.xlsx', 1) returning id""", String.class, store);
        jdbc.update("""
                insert into promo_leaflet_lines (leaflet_id, line_no, date_from, date_to, itm8, ean, name)
                values (?::uuid, 1, ?::date, ?::date, ?, '5900000000000', 'Gazetka')""",
                leaflet, today.minusDays(10), today.plusDays(10), itm8);
    }

    private void product(String itm8, String salePrice) {
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'BAZA.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, store);
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, sale_price, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, '5900000000000', 'Artykuł', ?::numeric, ?::uuid, ?::uuid)""",
                store, itm8, salePrice, productImport, productImport);
    }

    private String delivery(String itm8, int daysAgo, String quantity, String purchaseValue, String salePrice,
            String nomenclature) {
        String id = document("PZ", daysAgo);
        line(id, itm8, quantity, purchaseValue, salePrice, nomenclature, 0);
        return id;
    }

    private void correction(String deliveryId, String itm8, String quantity, String purchaseValue) {
        String id = document("WZ", 1);
        line(id, itm8, quantity, purchaseValue, null, null, 0);
        jdbc.update("""
                insert into delivery_corrections (id, delivery_id, correction_id, linked_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now())""", deliveryId, id);
    }

    private String document(String type, int daysAgo) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, supplier_code, supplier_name,
                    line_count, purchase_value, sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, '100', 'Dostawca 100', 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, store, type, String.valueOf(nextNumber++), today.minusDays(daysAgo) + " 10:00",
                documentImport, documentImport);
    }

    private void line(String documentId, String itm8, String quantity, String purchaseValue, String salePrice,
            String nomenclature, int vatRate) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, supplier_code, quantity, purchase_value,
                    sale_price_document, nomenclature_code, vat_rate)
                values (?::uuid, 1, ?, '100', ?::numeric, ?::numeric, ?::numeric, ?, ?)""",
                documentId, itm8, quantity, purchaseValue, salePrice, nomenclature, vatRate);
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
