package com.intermanage.backend;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
class PriceCheckIntegrationTests {

    private static final String CADENCIER_ITEM = "0000000000101";
    private static final String BASE_ITEM = "0000000000102";
    private static final String LOCAL_ITEM = "0000000000103";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reportsCadencierItemsBoughtOutsideBaseAndTracksReviews() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String storeNumber = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, storeId);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 3, 3, 0, 0, 0) returning id""", String.class, storeId);
        insertProduct(storeId, productImport, CADENCIER_ITEM, new BigDecimal("4.49"));
        insertProduct(storeId, productImport, BASE_ITEM, null);
        insertProduct(storeId, productImport, LOCAL_ITEM, null);
        String documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', ?::timestamp, ?::date, ?::date, 4, 4, 3, 0, 0) returning id""",
                String.class, storeId, today + " 08:00", today.minusDays(60), today);

        // Baza (538) i baza alkoholi (88110): artykuły z cadencier od nich nie trafiają do raportu.
        String baseDelivery = insertDocument(storeId, documentImport, "PZ", "100100", today.minusDays(10), "538");
        insertLine(baseDelivery, 1, BASE_ITEM, "538", "10", "29.90", "31.00");
        String alcoholDelivery = insertDocument(storeId, documentImport, "PZ", "100150", today.minusDays(9), "88110");
        insertLine(alcoholDelivery, 1, CADENCIER_ITEM, "88110", "10", "35.00", "37.00");
        // Lokalny dostawca: dwa artykuły z cadencier (marża 5,41% i 5%) i jeden spoza niej.
        String delivery = insertDocument(storeId, documentImport, "PZ", "100200", today.minusDays(7), "100");
        insertLine(delivery, 1, CADENCIER_ITEM, "100", "10", "35.00", "37.00");
        insertLine(delivery, 2, BASE_ITEM, "100", "5", "19.00", "20.00");
        insertLine(delivery, 3, LOCAL_ITEM, "100", "5", "19.00", "20.00");
        // Starsze niż 30 dni: poza raportem.
        String old = insertDocument(storeId, documentImport, "PZ", "100050", today.minusDays(40), "100");
        insertLine(old, 1, CADENCIER_ITEM, "100", "10", "35.00", "37.00");

        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodDays").value(30))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.cadencierCount").value(0))
                .andExpect(jsonPath("$.deliveries.length()").value(0));

        // Lista z innego sklepu jest odrzucana; ITM bez zer wiodących jest dopełniany do 13 cyfr.
        uploadCadencier(storeId, owner, "999", "28/10/2026  15:02:59", CADENCIER_ITEM)
                .andExpect(status().isConflict());
        uploadCadencier(storeId, owner, storeNumber, "28/10/2026  15:02:59", CADENCIER_ITEM, "102")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.addedCount").value(2))
                .andExpect(jsonPath("$.removedCount").value(0));

        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.cadencierCount").value(2))
                .andExpect(jsonPath("$.minMarginPercent").value(nullValue()))
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].id").value(delivery))
                .andExpect(jsonPath("$.deliveries[0].belowMinimumCount").value(0))
                .andExpect(jsonPath("$.deliveries[0].linesPurchaseValue").value(54.0))
                .andExpect(jsonPath("$.deliveries[0].excessValue").value(0))
                // tylko artykuł z ceną z bazy: (3,80 - 2,99) * 5
                .andExpect(jsonPath("$.deliveries[0].baseDifferenceValue").value(4.05))
                .andExpect(jsonPath("$.deliveries[0].lines.length()").value(2))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(CADENCIER_ITEM))
                .andExpect(jsonPath("$.deliveries[0].lines[0].marginPercent").value(5.41))
                .andExpect(jsonPath("$.deliveries[0].lines[0].targetPurchasePrice").value(nullValue()))
                .andExpect(jsonPath("$.deliveries[0].lines[0].cadencierPrice").value(4.49))
                .andExpect(jsonPath("$.deliveries[0].lines[1].itm8").value(BASE_ITEM))
                .andExpect(jsonPath("$.deliveries[0].lines[1].basePrice").value(2.99))
                .andExpect(jsonPath("$.deliveries[0].lines[1].baseDocumentId").value(baseDelivery));

        mvc.perform(put(url(storeId) + "/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minMarginPercent\":120}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url(storeId) + "/settings").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minMarginPercent\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minMarginPercent").value(20.0));

        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].belowMinimumCount").value(2))
                // (3,50 - 3,70 * 80%) * 10 = 5,40 i (3,80 - 4,00 * 80%) * 5 = 3,00
                .andExpect(jsonPath("$.deliveries[0].excessValue").value(8.4))
                .andExpect(jsonPath("$.deliveries[0].lines[0].belowMinimum").value(true))
                .andExpect(jsonPath("$.deliveries[0].lines[0].targetPurchasePrice").value(2.96));

        // Status wyjaśniania: zgłoszone dostawcy, cofnięcie wraca do "do wyjaśnienia", przywrócenie.
        String history = mvc.perform(post(url(storeId) + "/documents/" + delivery + "/reviews").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REPORTED\",\"note\":\"  mail do dostawcy  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REPORTED"))
                .andExpect(jsonPath("$[0].note").value("mail do dostawcy"))
                .andReturn().getResponse().getContentAsString();
        String reviewId = JsonPath.read(history, "$[0].id");
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].review.status").value("REPORTED"));
        mvc.perform(delete(url(storeId) + "/reviews/" + reviewId).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(url(storeId) + "/reviews/" + reviewId + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reviewId));

        // Korekta obniża cenę artykułu z cadencier: zostaje w raporcie, ale marża (21,62%) jest już w normie.
        String correction = insertDocument(storeId, documentImport, "WZ", "WZ0001", today.minusDays(5), "100");
        insertLine(correction, 1, CADENCIER_ITEM, "100", "0", "-6.00", "0");
        mvc.perform(post("/api/stores/" + storeId + "/documents/pz/" + delivery + "/corrections").cookie(owner)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + correction + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].correctionCount").value(1))
                .andExpect(jsonPath("$.deliveries[0].lines.length()").value(2))
                .andExpect(jsonPath("$.deliveries[0].belowMinimumCount").value(1))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(BASE_ITEM))
                .andExpect(jsonPath("$.deliveries[0].lines[1].belowMinimum").value(false))
                .andExpect(jsonPath("$.deliveries[0].excessValue").value(3.0));

        // Starsza lista jest odrzucana, nowsza bez jednego artykułu go usuwa i zawęża raport.
        uploadCadencier(storeId, owner, storeNumber, "27/10/2026  15:02:59", CADENCIER_ITEM)
                .andExpect(status().isConflict());
        uploadCadencier(storeId, owner, storeNumber, "29/10/2026  07:00:00", CADENCIER_ITEM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addedCount").value(0))
                .andExpect(jsonPath("$.removedCount").value(1));
        // Lista jest jedynym źródłem prawdy: kodu spoza nowej listy nie ma już w bazie.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
                "select itm8 from cadencier_items where store_id = ?::uuid", String.class, storeId))
                .containsExactly(CADENCIER_ITEM);
        mvc.perform(get(url(storeId) + "/cadencier").cookie(owner))
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.imports.length()").value(2));
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].lines.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value(CADENCIER_ITEM));

        mvc.perform(post(url(storeId) + "/documents/" + baseDelivery + "/reviews").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void matchesSupplierItemsByEanAndKeepsReviewedDeliveriesRegardlessOfDate() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        String storeNumber = jdbc.queryForObject("select number from stores where id = ?::uuid", String.class, storeId);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
        String documentImport = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, report_generated_at, period_from, period_to,
                    total_lines, saved_lines, created_count, updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', ?::timestamp, ?::date, ?::date, 3, 3, 3, 0, 0) returning id""",
                String.class, storeId, today + " 08:00", today.minusDays(60), today);
        // EAN z listy cadencier dla CADENCIER_ITEM to 5900000000101 (patrz cadencierReport).
        String baseDelivery = insertDocument(storeId, documentImport, "PZ", "200050", today.minusDays(20), "538");
        insertLine(baseDelivery, 1, "0000000000999", "538", "10", "30.00", "37.00");
        jdbc.update("update document_lines set ean = '5900000000101' where document_id = ?::uuid", baseDelivery);
        // Dostawca spoza bazy przywozi ten sam towar pod swoim ITM8 (99...); EAN z zerem wiodącym (GTIN-14).
        String delivery = insertDocument(storeId, documentImport, "PZ", "200100", today.minusDays(5), "200");
        insertLine(delivery, 1, "0000099000101", "200", "10", "35.00", "37.00");
        insertLine(delivery, 2, "0000099000102", "200", "10", "35.00", "37.00");
        jdbc.update("update document_lines set ean = '05900000000101' where document_id = ?::uuid and line_number = 1",
                delivery);
        jdbc.update("update document_lines set ean = '101' where document_id = ?::uuid and line_number = 2",
                delivery);
        // Starsze niż 30 dni: w raporcie dopiero ze statusem wyjaśniania.
        String old = insertDocument(storeId, documentImport, "PZ", "200010", today.minusDays(45), "200");
        insertLine(old, 1, "0000099000101", "200", "10", "35.00", "37.00");
        jdbc.update("update document_lines set ean = '5900000000101' where document_id = ?::uuid", old);

        uploadCadencier(storeId, owner, storeNumber, "28/10/2026  15:02:59", CADENCIER_ITEM)
                .andExpect(status().isCreated());

        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].id").value(delivery))
                // kod "101" to nie EAN: nie łączy się z cadencier
                .andExpect(jsonPath("$.deliveries[0].lines.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].lines[0].itm8").value("0000099000101"))
                .andExpect(jsonPath("$.deliveries[0].lines[0].cadencierItm8").value(CADENCIER_ITEM))
                // cena z bazy po EAN-ie, bo baza miała towar pod innym ITM8
                .andExpect(jsonPath("$.deliveries[0].lines[0].basePrice").value(3.0))
                .andExpect(jsonPath("$.deliveries[0].lines[0].baseDocumentId").value(baseDelivery));

        // Incydent zamówienia do bazy 11 dni przed przyjęciem: za dawno, przyjęcie zostaje do weryfikacji.
        uploadIncidents(storeId, owner, storeNumber + "_" + yymmdd(today.minusDays(16)) + "_W_NS_INCYDENTY.xlsx",
                storeNumber).andExpect(status().isCreated())
                .andExpect(jsonPath("$.incidentDate").value(today.minusDays(16).toString()))
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.addedCount").value(1));
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.incidentDays").value(10))
                .andExpect(jsonPath("$.deliveries[0].afterIncident").value(false))
                .andExpect(jsonPath("$.deliveries[0].lines[0].incidentDate").value(nullValue()));
        // Plik innego sklepu i nazwa bez daty są odrzucane.
        uploadIncidents(storeId, owner, "999_" + yymmdd(today) + "_INCYDENTY.xlsx", "999")
                .andExpect(status().isConflict());
        uploadIncidents(storeId, owner, "INCYDENTY.xlsx", storeNumber).andExpect(status().isBadRequest());
        // Incydent 8 dni przed przyjęciem (ITM8 z 8 cyframi z pliku = ITM8 z cadencier): osobna kategoria.
        uploadIncidents(storeId, owner, storeNumber + "_" + yymmdd(today.minusDays(13)) + "_" + yymmdd(today)
                + "_W_NS_INCYDENTY.xlsx", storeNumber).andExpect(status().isCreated());
        mvc.perform(get(url(storeId) + "/incidents").cookie(owner))
                .andExpect(jsonPath("$.lastIncidentDate").value(today.minusDays(13).toString()))
                .andExpect(jsonPath("$.imports.length()").value(2));
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].afterIncident").value(true))
                .andExpect(jsonPath("$.deliveries[0].lines[0].incidentDate").value(today.minusDays(13).toString()))
                .andExpect(jsonPath("$.deliveries[0].lines[0].incidentReason")
                        .value("Chwilowy brak w magazynie centralnym"));

        // Własne kategorie: rejon linii z nomenklatury, nazwa unikalna, usunięcie ze znacznikiem i przywróceniem.
        jdbc.update("update document_lines set nomenclature_code = '570101' where document_id = ?::uuid", delivery);
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries[0].lines[0].regionCode").value("57"));
        mvc.perform(post(url(storeId) + "/categories").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Napoje\",\"regionCodes\":[\"7\"]}"))
                .andExpect(status().isBadRequest());
        String category = mvc.perform(post(url(storeId) + "/categories").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Napoje \",\"regionCodes\":[\"57\",\"14\",\"57\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Napoje"))
                .andExpect(jsonPath("$.regionCodes.length()").value(2))
                .andExpect(jsonPath("$.regionCodes[0]").value("14"))
                .andReturn().getResponse().getContentAsString();
        String categoryId = JsonPath.read(category, "$.id");
        mvc.perform(post(url(storeId) + "/categories").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"napoje\",\"regionCodes\":[\"31\"]}"))
                .andExpect(status().isConflict());
        mvc.perform(put(url(storeId) + "/categories/" + categoryId).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Napoje\",\"regionCodes\":[\"57\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCodes.length()").value(1));
        mvc.perform(delete(url(storeId) + "/categories/" + categoryId).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(url(storeId) + "/categories").cookie(owner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(url(storeId) + "/categories/" + categoryId + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(url(storeId) + "/categories").cookie(owner))
                .andExpect(jsonPath("$[0].name").value("Napoje"));

        mvc.perform(post(url(storeId) + "/documents/" + old + "/reviews").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(url(storeId)).cookie(owner))
                .andExpect(jsonPath("$.deliveries.length()").value(2))
                .andExpect(jsonPath("$.deliveries[1].id").value(old))
                .andExpect(jsonPath("$.deliveries[1].review.status").value("RESOLVED"))
                .andExpect(jsonPath("$.deliveries[1].lines[0].cadencierItm8").value(CADENCIER_ITEM));
    }

    private static String url(String storeId) {
        return "/api/stores/" + storeId + "/reports/price-check";
    }

    private ResultActions uploadCadencier(String storeId, Cookie owner, String storeNumber, String date,
            String... itm8s) throws Exception {
        return mvc.perform(multipart(url(storeId) + "/cadencier")
                .file(new MockMultipartFile("file", "obecne w cad.xlsx", "application/octet-stream",
                        cadencierReport(storeNumber, date, itm8s)))
                .cookie(owner).with(csrf()));
    }

    private static String yymmdd(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    }

    /** Plik incydentów w układzie z prawdziwego: nagłówek w pierwszym wierszu, ITM8 z 8 cyframi, jeden artykuł. */
    private ResultActions uploadIncidents(String storeId, Cookie owner, String fileName, String storeNumber)
            throws Exception {
        List<String[]> rows = List.of(
                new String[] {"Baza", "Sklep", "Nazwa Sklepu", "Przyczyna", "Komentarz", "ITM8", "Marka", "Nazwa",
                        "EAN", "Pakow", "Zamówiono", "Dostarczono", "ITM8 Odpowiednika", "Kod Incydentu",
                        "Czy Zamawiał"},
                new String[] {"095", storeNumber, "IM TEST", "Chwilowy brak w magazynie centralnym",
                        "prosimy o ponowienie zamówienia", "00000101", "MARKA", "ARTYKUŁ", "5900000000101", "20",
                        "1", "0", "", "005", "TAK"});
        return mvc.perform(multipart(url(storeId) + "/incidents")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", workbook(rows)))
                .cookie(owner).with(csrf()));
    }

    /** Lista w układzie z prawdziwego pliku: nagłówek sklepu, data, tytuł, kolumny ITM i EAN, wiersze i stopka. */
    private static byte[] cadencierReport(String storeNumber, String date, String... itm8s) throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber});
        rows.add(new String[] {"Data : " + date});
        rows.add(new String[] {"OBECNE W CAD"});
        rows.add(new String[] {});
        rows.add(new String[] {"Lista Automatyczna - 1996, OBECNE W CADENCIER (posort. wg. ITM8 Prio)"});
        rows.add(new String[] {});
        rows.add(new String[] {"ITM", "EAN"});
        for (String itm8 : itm8s) {
            rows.add(new String[] {itm8, "590" + itm8.substring(Math.max(0, itm8.length() - 10))});
        }
        rows.add(new String[] {});
        rows.add(new String[] {"Ilość linii: :" + String.format("%06d", itm8s.length)});
        return workbook(rows);
    }

    private void insertProduct(String storeId, String importId, String itm8, BigDecimal cadencierPrice) {
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, unit, net_content, purchase_price, sale_price,
                    cadencier_price, active, first_seen_import_id, last_seen_import_id)
                values (?::uuid, ?, ?, 'ARTYKUŁ', 'szt.', 1, 1, 4.49, ?, true, ?::uuid, ?::uuid)""",
                storeId, itm8, "59" + itm8.substring(2), cadencierPrice, importId, importId);
    }

    private String insertDocument(String storeId, String importId, String type, String number, LocalDate date,
            String supplierCode) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, supplier_code, supplier_name,
                    line_count, purchase_value, sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, ?, ?, 1, 0, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, date + " 10:00", supplierCode, "Dostawca " + supplierCode,
                importId, importId);
    }

    private void insertLine(String documentId, int number, String itm8, String supplierCode, String quantity,
            String purchaseValue, String saleValueNet) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal purchase = new BigDecimal(purchaseValue);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, supplier_code, quantity, purchase_price,
                    purchase_value, sale_value_net, sale_value_gross)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)""",
                documentId, number, itm8, supplierCode, qty,
                qty.signum() == 0 ? null : purchase.divide(qty, 4, java.math.RoundingMode.HALF_UP), purchase,
                new BigDecimal(saleValueNet), new BigDecimal(saleValueNet).multiply(new BigDecimal("1.05")));
    }

    private String createStore(Cookie owner) throws Exception {
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

    /** Minimalny skoroszyt .xlsx, wszystkie wartości jako teksty z tabeli współdzielonej. */
    private static byte[] workbook(List<String[]> rows) throws IOException {
        List<String> shared = new ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                sheet.append("<c r=\"").append((char) ('A' + c)).append(r + 1).append("\" t=\"s\"><v>")
                        .append(shared.size()).append("</v></c>");
                shared.add(cells[c]);
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        shared.forEach(s -> strings.append("<si><t xml:space=\"preserve\">")
                .append(s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</t></si>"));
        strings.append("</sst>");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putEntry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Mercalys\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putEntry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                    + "</Relationships>");
            putEntry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            putEntry(zip, "xl/sharedStrings.xml", strings.toString());
        }
        return bytes.toByteArray();
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
