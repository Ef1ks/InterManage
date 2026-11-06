package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.bakery.component.BakeryScanReader;
import com.intermanage.backend.bakery.component.DeliveryNotes;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Weryfikacja piekarni: bon, faktura z numerami WZ, ilości z WZ i porównanie z fakturą z korektami i bonem. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class BakeryCheckIntegrationTests {

    private static final String SELLER_NIP = "9261674156";
    private static final String BREAD_EAN = "5901549170344";
    private static final String ROLL_EAN = "5901549170351";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    BakeryScanReader scanReader;

    private Cookie owner;
    private String storeId;
    private String supplierId;
    private String nip;
    private String breadId;
    private String rollId;
    private String importId;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String store = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        storeId = JsonPath.read(store, "$.id");
        nip = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("""
                insert into ksef_connections (store_id, nip, certificate_pem, private_key_pem, password_encrypted,
                    certificate_subject, certificate_valid_to, updated_at)
                values (?::uuid, ?, 'x', 'x', 'x', 'TEST', now() + interval '1 year', now())""", storeId, nip);
        supplierId = jdbc.queryForObject("""
                insert into suppliers (store_id, code, keyword, name, nip)
                values (?::uuid, '50', 'SIKORA', 'ZAKŁAD PRODUKCYJNY PIEKARNIA SIKORA SPOLKA CYWILNA', ?)
                returning id::text""", String.class, storeId, SELLER_NIP);
        String productImport = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 3, 3, 0, 0, 0) returning id""", String.class, storeId);
        // Kartoteka trzyma nazwę dostawcy uciętą do 30 znaków i bez polskich liter.
        breadId = product(productImport, "0000000001112", BREAD_EAN, "CHLEB KROJONY 350G", "ZAKLAD PRODUKCYJNY PIEKARNIA");
        rollId = product(productImport, "0000000001113", ROLL_EAN, "BULKA KAJZERKA", "ZAKLAD PRODUKCYJNY PIEKARNIA");
        product(productImport, "0000000001114", "5900000000012", "MLEKO", "INNY DOSTAWCA");
        importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 3, 3, 3, 0, 0) returning id""", String.class, storeId);
        day = LocalDate.now().minusDays(5);
    }

    @Test
    void bonCreatesWzTilesAndSummaryFindsWzMistake() throws Exception {
        String invoiceId = invoice("2039/10/2026/FA", "Vat", invoiceXml());
        String correctionId = invoice("209/10/2026/KS", "Kor", correctionXml());
        jdbc.update("""
                insert into ksef_invoice_corrected (invoice_id, invoice_number, ksef_number, issue_date)
                values (?::uuid, '2039/10/2026/FA', null, null)""", UUID.fromString(correctionId));
        jdbc.update("update ksef_invoices set corrected_read_at = now() where id = ?::uuid", correctionId);
        String bonId = bon("999300", "2039/10/2026/FA", new String[][] {
                {"1", "0000000001112", BREAD_EAN, "CHLEB KROJONY 350G", "10", "20.00"},
                {"2", "0000000001113", ROLL_EAN, "BULKA KAJZERKA", "5", "5.00"}});
        // Korekta od dostawcy zabiera 2 chleby, więc bon po korektach ma 8.
        String correctionDocument = document("WZ", "WZ0001", null, 5, "-4.00");
        line(correctionDocument, new String[] {"1", "0000000001112", BREAD_EAN, "CHLEB KROJONY 350G", "-2", "-4.00"});
        jdbc.update("""
                insert into delivery_corrections (id, delivery_id, correction_id, linked_at)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now())""", UUID.fromString(bonId),
                UUID.fromString(correctionDocument));

        mvc.perform(get(url("/deliveries")).param("supplierId", supplierId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bonNumber").value("9993"))
                .andExpect(jsonPath("$[0].invoices[0]").value("2039/10/2026/FA"))
                .andExpect(jsonPath("$[0].correctionCount").value(1))
                .andExpect(jsonPath("$[0].correctedValue").value(21.0));

        String created = create(bonId);
        String id = JsonPath.read(created, "$.id");
        assertThat(JsonPath.<List<String>>read(created, "$.wz[*].number"))
                .containsExactly("14803/10/2026/DD", "14858/10/2026/DD");
        assertThat(JsonPath.<String>read(created, "$.wz[0].deliveryDate")).isEqualTo("2026-10-21");
        assertThat(JsonPath.<Boolean>read(created, "$.wz[0].dateEstimated")).isFalse();
        assertThat(JsonPath.<String>read(created, "$.wz[0].sourceInvoice")).isEqualTo("2039/10/2026/FA");
        assertThat(JsonPath.<List<String>>read(created, "$.articles[*].name"))
                .containsExactly("BULKA KAJZERKA", "CHLEB KROJONY 350G");
        assertThat(JsonPath.<List<String>>read(created, "$.invoices[*].invoiceNumber"))
                .containsExactly("2039/10/2026/FA", "209/10/2026/KS");
        assertThat(JsonPath.<Boolean>read(created, "$.invoices[1].pinned")).isFalse();
        assertThat(invoiceId).isNotBlank();

        // Ten sam bon drugi raz otwiera tę samą weryfikację.
        assertThat(JsonPath.<String>read(create(bonId), "$.id")).isEqualTo(id);

        // 6 + 3 chleby z WZ przy 10 - 2 z faktury z korektą: WZ ma o jeden więcej. Bułki zgodne.
        String first = JsonPath.read(created, "$.wz[0].id");
        String second = JsonPath.read(created, "$.wz[1].id");
        saveWz(id, first, "14803/10/2026/DD", "2026-10-21", "[%s,%s]".formatted(line(breadId, "6"), line(rollId, "5")));
        saveWz(id, second, "14858/10/2026/DD", "2026-10-22", "[%s]".formatted(line(breadId, "3")));

        mvc.perform(get(url("/" + id + "/comparison")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasBon").value(true))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].name").value("CHLEB KROJONY 350G"))
                .andExpect(jsonPath("$.rows[0].status").value("WZ"))
                .andExpect(jsonPath("$.rows[0].wzQuantity").value(9.0))
                .andExpect(jsonPath("$.rows[0].invoiceQuantity").value(10.0))
                .andExpect(jsonPath("$.rows[0].correctionQuantity").value(-2.0))
                .andExpect(jsonPath("$.rows[0].finalQuantity").value(8.0))
                .andExpect(jsonPath("$.rows[0].bonQuantity").value(8.0))
                .andExpect(jsonPath("$.rows[0].difference").value(1.0))
                .andExpect(jsonPath("$.rows[0].differenceValue").value(2.0))
                .andExpect(jsonPath("$.rows[1].status").value("OK"))
                .andExpect(jsonPath("$.totals.finalValue").value(21.0))
                .andExpect(jsonPath("$.totals.bonValue").value(21.0))
                .andExpect(jsonPath("$.totals.differenceValue").value(2.0))
                .andExpect(jsonPath("$.totals.differences").value(1));

        // Lista dostawcy pokazuje weryfikację, a bon wie, w której jest.
        mvc.perform(get(url("")).param("supplierId", supplierId).cookie(owner))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].wzCount").value(2))
                .andExpect(jsonPath("$[0].filledWz").value(2))
                .andExpect(jsonPath("$[0].bons[0]").value("9993"));
        mvc.perform(get(url("/deliveries")).param("supplierId", supplierId).cookie(owner))
                .andExpect(jsonPath("$[0].checkId").value(id));
    }

    @Test
    void invoiceLineWithoutArticleUsesDataEntryLink() throws Exception {
        invoice("2039/10/2026/FA", "Vat", invoiceXml().replace(ROLL_EAN, "").replace("<GTIN></GTIN>",
                "<Indeks>BUL-1</Indeks>"));
        String bonId = bon("999300", "2039/10/2026/FA", new String[][] {
                {"1", "0000000001112", BREAD_EAN, "CHLEB KROJONY 350G", "10", "20.00"}});
        String id = JsonPath.read(create(bonId), "$.id");

        mvc.perform(get(url("/" + id + "/comparison")).cookie(owner))
                .andExpect(jsonPath("$.rows[?(@.status == 'NO_ARTICLE')].linkCode").value(contains("BUL-1")));

        // To samo przypięcie co we wprowadzaniu danych: po nim pozycja łączy się z artykułem.
        mvc.perform(post("/api/stores/" + storeId + "/data-entry/links").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerNip\":\"" + SELLER_NIP + "\",\"code\":\"BUL-1\",\"itm8\":\"0000000001113\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(url("/" + id + "/comparison")).cookie(owner))
                .andExpect(jsonPath("$.rows[?(@.status == 'NO_ARTICLE')]", hasSize(0)))
                .andExpect(jsonPath("$.rows[?(@.itm8 == '0000000001113')].invoiceQuantity").value(contains(5)));
    }

    @Test
    void manualCheckScanAndDeletedWz() throws Exception {
        String created = mvc.perform(post(url("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wz", hasSize(0)))
                .andExpect(jsonPath("$.scanReading").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String wz = mvc.perform(post(url("/" + id + "/wz")).cookie(owner).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String wzId = JsonPath.read(wz, "$.id");

        // Artykuł spoza listy dostawcy zostaje jako dopisany ręcznie także bez ilości, reszta bez ilości znika.
        String manual = "{\"productId\":\"%s\",\"quantity\":null,\"scannedQuantity\":null,\"manual\":true}".formatted(breadId);
        saveWz(id, wzId, "W1", "2026-10-21", "[%s,%s]".formatted(manual, line(rollId, "0")));
        mvc.perform(get(url("/" + id)).cookie(owner))
                .andExpect(jsonPath("$.wz[0].lines", hasSize(1)))
                .andExpect(jsonPath("$.wz[0].lines[0].manual").value(true));

        // Odczyt skanu: propozycja wraca, niczego nie zapisuje.
        mvc.perform(multipart(url("/" + id + "/wz/" + wzId + "/scan")).file(new MockMultipartFile("file", "wz.png",
                                "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1}))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanContentType").value("image/png"));
        when(scanReader.read(any(), any(), any())).thenReturn(new BakeryScanReader.Reading("W1", day, List.of(
                new BakeryScanReader.Line("KAJZERKA", new BigDecimal("12"), null, 1),
                new BakeryScanReader.Line("NIEZNANY", new BigDecimal("3"), null, 0))));
        mvc.perform(post(url("/" + id + "/wz/" + wzId + "/scan/read")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].productId").value(rollId))
                .andExpect(jsonPath("$.lines[1].productId").doesNotExist());

        // Usunięty WZ znika, „Przywróć” oddaje go z ilościami.
        mvc.perform(delete(url("/" + id + "/wz/" + wzId)).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(url("/" + id)).cookie(owner)).andExpect(jsonPath("$.wz", hasSize(0)));
        mvc.perform(post(url("/" + id + "/wz/" + wzId + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)));

        mvc.perform(delete(url("/" + id)).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(url("/" + id)).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(post(url("/" + id + "/restore")).cookie(owner).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void deliveryNotesFromInvoiceFields() {
        // Sikora: numer i data w polu WZ.
        assertThat(DeliveryNotes.parse(List.of("DD nr 14858/10/2026/DD (2026-10-22)",
                "DD nr 14803/10/2026/DD (2026-10-21)"), null, null))
                .containsExactly(new DeliveryNotes.Note("14803/10/2026/DD", LocalDate.of(2026, 10, 21), false),
                        new DeliveryNotes.Note("14858/10/2026/DD", LocalDate.of(2026, 10, 22), false));
        // Sofian: same numery i okres faktury z jedną WZ dziennie; 999 jest przed 1000.
        LocalDate from = LocalDate.of(2026, 10, 26);
        assertThat(DeliveryNotes.parse(List.of("1000", "999"), from, from.plusDays(1)))
                .containsExactly(new DeliveryNotes.Note("999", from, true),
                        new DeliveryNotes.Note("1000", from.plusDays(1), true));
        // Inna liczba WZ niż dni okresu albo brak okresu: daty do wpisania.
        assertThat(DeliveryNotes.parse(List.of("1", "2", "1"), from, from.plusDays(5)))
                .containsExactly(new DeliveryNotes.Note("1", null, false), new DeliveryNotes.Note("2", null, false));
    }

    private String create(String bonId) throws Exception {
        return mvc.perform(post(url("")).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplierId + "\",\"documentId\":\"" + bonId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void saveWz(String id, String wzId, String number, String date, String lines) throws Exception {
        mvc.perform(put(url("/" + id + "/wz/" + wzId)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"%s\",\"deliveryDate\":\"%s\",\"lines\":%s}".formatted(number, date, lines)))
                .andExpect(status().isOk());
    }

    private static String line(String productId, String quantity) {
        return "{\"productId\":\"%s\",\"quantity\":%s,\"scannedQuantity\":null,\"manual\":false}"
                .formatted(productId, quantity);
    }

    private String url(String path) {
        return "/api/stores/" + storeId + "/bakery-checks" + path;
    }

    private String product(String productImport, String itm8, String ean, String name, String supplier) {
        return jdbc.queryForObject("""
                insert into products (store_id, itm8, ean, name, supplier, active, first_seen_import_id,
                    last_seen_import_id)
                values (?::uuid, ?, ?, ?, ?, true, ?::uuid, ?::uuid) returning id::text""",
                String.class, storeId, itm8, ean, name, supplier, productImport, productImport);
    }

    private String bon(String number, String invoiceNumber, String[][] lines) {
        BigDecimal value = java.util.Arrays.stream(lines).map(line -> new BigDecimal(line[5]))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String id = document("PZ", number, invoiceNumber, 10, value.toPlainString());
        for (String[] line : lines) {
            line(id, line);
        }
        return id;
    }

    private String document(String type, String number, String invoiceNumber, int lines, String value) {
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, supplier_code, supplier_document_number, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?::timestamp, ?, ?::numeric, 0, '50', ?, ?::uuid, ?::uuid) returning id::text""",
                String.class, storeId, type, number, day.toString(), lines, value, invoiceNumber, importId, importId);
    }

    private void line(String documentId, String[] line) {
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, name, quantity, purchase_value,
                    supplier_code)
                values (?::uuid, ?::int, ?, ?, ?, ?::numeric, ?::numeric, '50')""", UUID.fromString(documentId),
                line[0], line[1], line[2], line[3], line[4], line[5]);
    }

    private String invoice(String number, String type, String xml) {
        String id = jdbc.queryForObject("""
                insert into ksef_invoices (id, ksef_number, invoice_number, issue_date, invoicing_date,
                    permanent_storage_date, seller_nip, seller_name, net_amount, gross_amount, vat_amount, currency,
                    invoice_type, has_attachment, created_at, updated_at, nip)
                values (gen_random_uuid(), ?, ?, ?::date, now(), now(), ?, 'PIEKARNIA SIKORA', 100, 105, 5, 'PLN', ?,
                    false, now(), now(), ?) returning id::text""", String.class,
                SELLER_NIP + "-" + UUID.randomUUID().toString().substring(0, 20).toUpperCase(), number, day.toString(),
                SELLER_NIP, type, nip);
        jdbc.update("insert into ksef_invoice_xml (invoice_id, content, downloaded_at) values (?::uuid, ?, now())",
                UUID.fromString(id), xml.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private static String invoiceXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Faktura xmlns="http://crd.gov.pl/wzor/2025/06/25/13775/">
                  <Naglowek><KodFormularza kodSystemowy="FA (3)" wersjaSchemy="1-0E">FA</KodFormularza></Naglowek>
                  <Podmiot1><DaneIdentyfikacyjne><NIP>9261674156</NIP><Nazwa>PIEKARNIA SIKORA</Nazwa>
                    </DaneIdentyfikacyjne></Podmiot1>
                  <Podmiot2><DaneIdentyfikacyjne><NIP>5265877635</NIP><Nazwa>TRYTOMA</Nazwa></DaneIdentyfikacyjne>
                    </Podmiot2>
                  <Fa>
                    <KodWaluty>PLN</KodWaluty><P_1>2026-10-31</P_1><P_2>2039/10/2026/FA</P_2>
                    <WZ>DD nr 14803/10/2026/DD (2026-10-21)</WZ><WZ>DD nr 14858/10/2026/DD (2026-10-22)</WZ>
                    <P_15>26.25</P_15><RodzajFaktury>VAT</RodzajFaktury>
                    <FaWiersz><NrWierszaFa>1</NrWierszaFa><P_7>CHLEB KR 350G</P_7><GTIN>%s</GTIN>
                      <P_8A>SZT</P_8A><P_8B>10</P_8B><P_9A>2.00</P_9A><P_11>20.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>2</NrWierszaFa><P_7>BULKA</P_7><GTIN>%s</GTIN>
                      <P_8A>SZT</P_8A><P_8B>5</P_8B><P_9A>1.00</P_9A><P_11>5.00</P_11><P_12>5</P_12></FaWiersz>
                    <FaWiersz><NrWierszaFa>3</NrWierszaFa><P_7>KAUCJA BUTELKA</P_7>
                      <P_8A>SZT</P_8A><P_8B>2</P_8B><P_9A>0.50</P_9A><P_11>1.00</P_11><P_12>23</P_12></FaWiersz>
                  </Fa>
                </Faktura>""".formatted(BREAD_EAN, ROLL_EAN);
    }

    private static String correctionXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Faktura xmlns="http://crd.gov.pl/wzor/2025/06/25/13775/">
                  <Naglowek><KodFormularza kodSystemowy="FA (3)" wersjaSchemy="1-0E">FA</KodFormularza></Naglowek>
                  <Podmiot1><DaneIdentyfikacyjne><NIP>9261674156</NIP><Nazwa>PIEKARNIA SIKORA</Nazwa>
                    </DaneIdentyfikacyjne></Podmiot1>
                  <Podmiot2><DaneIdentyfikacyjne><NIP>5265877635</NIP><Nazwa>TRYTOMA</Nazwa></DaneIdentyfikacyjne>
                    </Podmiot2>
                  <Fa>
                    <KodWaluty>PLN</KodWaluty><P_1>2026-10-31</P_1><P_2>209/10/2026/KS</P_2>
                    <WZ>1582/08/2026/WZ</WZ>
                    <P_15>-4.20</P_15><RodzajFaktury>KOR</RodzajFaktury>
                    <FaWiersz><NrWierszaFa>1</NrWierszaFa><P_7>CHLEB KR 350G</P_7><GTIN>%s</GTIN>
                      <P_8A>SZT</P_8A><P_8B>-2</P_8B><P_9A>2.00</P_9A><P_11>-4.00</P_11><P_12>5</P_12></FaWiersz>
                  </Fa>
                </Faktura>""".formatted(BREAD_EAN);
    }
}
