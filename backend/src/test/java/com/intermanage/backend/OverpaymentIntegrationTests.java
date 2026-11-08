package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Przepłacone artykuły: każde przyjęcie z 30 dni kontra trzy poprzednie dostawy. Każdy artykuł ma osobny numer, a jego
 * zwykła dostawa to 10 szt. po 10 zł.
 */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class OverpaymentIntegrationTests {

    private static final String OVERPAID = "0000000000201";
    private static final String WITHIN_THRESHOLD = "0000000000202";
    private static final String CHEAPER = "0000000000203";
    private static final String LARGE_DELIVERY = "0000000000204";
    private static final String LEAFLET_REFUND = "0000000000205";
    private static final String CORRECTED = "0000000000206";
    private static final String SYMBOLIC = "0000000000207";
    private static final String LOWEST_NOT_LAST = "0000000000208";

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
    }

    @Test
    void reportsOnlyPricesAboveThresholdOverLowestOfThreeNormalDeliveries() throws Exception {
        regularDeliveries(OVERPAID);
        String overpaid = delivery(OVERPAID, 5, "10", "106.00");

        regularDeliveries(WITHIN_THRESHOLD);
        delivery(WITHIN_THRESHOLD, 5, "10", "104.00");

        regularDeliveries(CHEAPER);
        delivery(CHEAPER, 5, "10", "80.00");

        // 100 szt. po 5 zł to krótki termin: bez tej dostawy 10 zł jest zwykłą ceną.
        regularDeliveries(LARGE_DELIVERY);
        delivery(LARGE_DELIVERY, 20, "100", "500.00");
        delivery(LARGE_DELIVERY, 5, "10", "100.00");

        // Baza oddała 40 zł korektą za gazetkę: cena odniesienia zostaje z bonu, więc 10 zł nie jest przepłaceniem.
        regularDeliveries(LEAFLET_REFUND);
        String refunded = delivery(LEAFLET_REFUND, 20, "10", "100.00");
        correction(refunded, LEAFLET_REFUND, "0", "-40.00");
        delivery(LEAFLET_REFUND, 5, "10", "100.00");

        // Korekta od dostawcy wyrównała cenę: przyjęcie po korektach nie jest przepłacone.
        regularDeliveries(CORRECTED);
        String corrected = delivery(CORRECTED, 5, "10", "120.00");
        correction(corrected, CORRECTED, "0", "-20.00");

        // Cena 0,01 zł nie jest ceną zakupu.
        delivery(SYMBOLIC, 40, "10", "0.10");
        delivery(SYMBOLIC, 5, "10", "50.00");

        // Liczy się najniższa z trzech, nie ostatnia.
        delivery(LOWEST_NOT_LAST, 70, "10", "90.00");
        delivery(LOWEST_NOT_LAST, 60, "10", "100.00");
        delivery(LOWEST_NOT_LAST, 50, "10", "100.00");
        delivery(LOWEST_NOT_LAST, 5, "10", "100.00");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodDays").value(30))
                .andExpect(jsonPath("$.thresholdPercent").value(5))
                .andExpect(jsonPath("$.referenceCount").value(3))
                .andExpect(jsonPath("$.deliveries", hasSize(2)))
                .andExpect(jsonPath("$.deliveries[?(@.id == '" + overpaid + "')].excessValue").value(6.0))
                .andExpect(jsonPath("$.deliveries[?(@.id == '" + overpaid + "')].lines[0].itm8").value(OVERPAID))
                .andExpect(jsonPath("$.deliveries[?(@.id == '" + overpaid + "')].lines[0].referencePrice").value(10.0))
                .andExpect(jsonPath("$.deliveries[?(@.id == '" + overpaid + "')].lines[0].overpaidPercent").value(6.0))
                .andExpect(jsonPath("$.deliveries[?(@.id == '" + overpaid + "')].lines[0].references.length()")
                        .value(3))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(OVERPAID, LOWEST_NOT_LAST)));
    }

    @Test
    void statusStaysWithReportAndKeepsOlderDelivery() throws Exception {
        regularDeliveries(OVERPAID);
        String old = delivery(OVERPAID, 35, "10", "120.00");

        mvc.perform(get(url()).cookie(owner)).andExpect(jsonPath("$.deliveries", hasSize(0)));

        String json = mvc.perform(post(url() + "/documents/" + old + "/reviews").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REPORTED\",\"note\":\"mail do dostawcy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        // Przyjęcie ze statusem zostaje, choć jest starsze niż 30 dni, i dalej pokazuje przepłacony artykuł.
        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.deliveries[0].id").value(old))
                .andExpect(jsonPath("$.deliveries[0].review.status").value("REPORTED"))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(OVERPAID));

        // Status tego raportu nie jest statusem zakupu bazowego poza bazą.
        assertThat(jdbc.queryForObject(
                "select report from price_check_reviews where document_id = ?::uuid", String.class, old))
                .isEqualTo("OVERPAYMENT");

        mvc.perform(delete("/api/stores/" + store + "/reports/price-check/reviews/" + JsonPath.read(json, "$[0].id"))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries", hasSize(0)))
                .andExpect(jsonPath("$.documentsCoveredUntil").value(today.toString()));
    }

    @Test
    void skipsNomenclaturesOutsideReport() throws Exception {
        for (String nomenclature : List.of("240101", "260101", "360101", "460101", "140101", "390101")) {
            String itm8 = "00000000003" + nomenclature.substring(0, 2);
            regularDeliveries(itm8);
            delivery(itm8, 5, "10", "120.00");
            jdbc.update("update document_lines set nomenclature_code = ? where itm8 = ?", nomenclature, itm8);
        }
        // Z rejonów 14 i 39 odpadają tylko owoce z warzywami i papierosy, a kod może mieć spacje.
        regularDeliveries(OVERPAID);
        delivery(OVERPAID, 5, "10", "120.00");
        jdbc.update("update document_lines set nomenclature_code = '14 02 01' where itm8 = ?", OVERPAID);
        regularDeliveries(WITHIN_THRESHOLD);
        delivery(WITHIN_THRESHOLD, 5, "10", "120.00");
        jdbc.update("update document_lines set nomenclature_code = '390201' where itm8 = ?", WITHIN_THRESHOLD);

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(OVERPAID, WITHIN_THRESHOLD)));
    }

    @Test
    void skipsKolporter() throws Exception {
        jdbc.update("""
                insert into suppliers (store_id, code, name, nip)
                values (?::uuid, '52', 'KOLPORTER SP. Z O.O.', '9591452482')""", store);
        regularDeliveries(OVERPAID);
        delivery(OVERPAID, 5, "10", "120.00");
        regularDeliveries(WITHIN_THRESHOLD);
        delivery(WITHIN_THRESHOLD, 5, "10", "120.00");
        jdbc.update("update document_lines set supplier_code = '52' where itm8 = ?", WITHIN_THRESHOLD);

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(OVERPAID)));
    }

    @Test
    void skipsSuppliersChosenInSettings() throws Exception {
        jdbc.update("insert into suppliers (store_id, code, name) values (?::uuid, '100', 'Dostawca 100')", store);
        regularDeliveries(OVERPAID);
        delivery(OVERPAID, 5, "10", "120.00");
        regularDeliveries(WITHIN_THRESHOLD);
        delivery(WITHIN_THRESHOLD, 5, "10", "120.00");
        jdbc.update("update document_lines set supplier_code = '200' where itm8 = ?", WITHIN_THRESHOLD);
        // Jedyna tańsza dostawa jest od pomijanego dostawcy, więc po pominięciu cena nie jest już przepłacona.
        regularDeliveries(CHEAPER);
        delivery(CHEAPER, 5, "10", "100.00");
        jdbc.update("update document_lines set supplier_code = '200' where itm8 = ?", CHEAPER);
        delivery(CHEAPER, 30, "10", "50.00");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.suppliers[0].code").value("100"))
                .andExpect(jsonPath("$.excludedSuppliers", hasSize(0)))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8",
                        containsInAnyOrder(OVERPAID, WITHIN_THRESHOLD, CHEAPER)));

        mvc.perform(put(url() + "/excluded-suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[\"100\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedSuppliers[0].name").value("Dostawca 100"))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(WITHIN_THRESHOLD)));

        // Lista jest osobna dla każdego raportu.
        mvc.perform(get("/api/stores/" + store + "/reports/low-margins").cookie(owner))
                .andExpect(jsonPath("$.excludedSuppliers", hasSize(0)));

        mvc.perform(put(url() + "/excluded-suppliers").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[]}"))
                .andExpect(jsonPath("$.excludedSuppliers", hasSize(0)))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8",
                        containsInAnyOrder(OVERPAID, WITHIN_THRESHOLD, CHEAPER)));
    }

    @Test
    void referenceHasSupplierName() throws Exception {
        regularDeliveries(OVERPAID);
        delivery(OVERPAID, 5, "10", "120.00");
        jdbc.update("update document_lines set supplier_name = 'Dostawca 100' where itm8 = ?", OVERPAID);

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].lines[0].references[0].supplierCode").value("100"))
                .andExpect(jsonPath("$.deliveries[0].lines[0].references[0].supplierName").value("Dostawca 100"));
    }

    @Test
    void oldDeliveriesAreNotReferencesWhenLimited() throws Exception {
        // Ostatnia dostawa pół roku temu: wzrost ceny nie jest przepłaceniem.
        delivery(OVERPAID, 165, "10", "100.00");
        delivery(OVERPAID, 5, "10", "120.00");

        // Świeższa dostawa zostaje, a przepłacenie liczy się już tylko od niej.
        delivery(LOWEST_NOT_LAST, 150, "10", "50.00");
        delivery(LOWEST_NOT_LAST, 30, "10", "100.00");
        delivery(LOWEST_NOT_LAST, 5, "10", "120.00");

        // Stara cena była najniższa, ale nowsza jest bliska sprawdzanej.
        delivery(WITHIN_THRESHOLD, 150, "10", "50.00");
        delivery(WITHIN_THRESHOLD, 30, "10", "100.00");
        delivery(WITHIN_THRESHOLD, 5, "10", "104.00");

        mvc.perform(get(url()).cookie(owner))
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8",
                        containsInAnyOrder(OVERPAID, LOWEST_NOT_LAST, WITHIN_THRESHOLD)));
        mvc.perform(get(url()).param("maxReferenceDays", "90").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[*].lines[*].itm8", containsInAnyOrder(LOWEST_NOT_LAST)))
                .andExpect(jsonPath("$.deliveries[0].lines[0].referencePrice").value(10.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].excessValue").value(20.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].references", hasSize(1)));
    }

    @Test
    void referenceDaysMustFitHistory() throws Exception {
        mvc.perform(get(url()).param("maxReferenceDays", "0").cookie(owner)).andExpect(status().isBadRequest());
        mvc.perform(get(url()).param("maxReferenceDays", "181").cookie(owner)).andExpect(status().isBadRequest());
    }

    @Test
    void emptyStoreHasEmptyReport() throws Exception {
        mvc.perform(get(url()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries", hasSize(0)));
    }

    private String url() {
        return "/api/stores/" + store + "/reports/overpayments";
    }

    /** Trzy zwykłe dostawy sprzed okresu raportu. */
    private void regularDeliveries(String itm8) {
        delivery(itm8, 60, "10", "100.00");
        delivery(itm8, 50, "10", "100.00");
        delivery(itm8, 40, "10", "100.00");
    }

    private String delivery(String itm8, int daysAgo, String quantity, String purchaseValue) {
        String id = document("PZ", daysAgo);
        line(id, itm8, quantity, purchaseValue);
        return id;
    }

    private void correction(String deliveryId, String itm8, String quantity, String purchaseValue) {
        String id = document("WZ", 1);
        line(id, itm8, quantity, purchaseValue);
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

    private void line(String documentId, String itm8, String quantity, String purchaseValue) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, supplier_code, quantity, purchase_value)
                values (?::uuid, 1, ?, '100', ?::numeric, ?::numeric)""", documentId, itm8, quantity, purchaseValue);
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
