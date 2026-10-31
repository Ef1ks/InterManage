package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.dto.KsefInvoiceMetadata;
import com.intermanage.backend.ksef.service.KsefCorrectedInvoiceService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Moduł faktur KSeF z podmienionym klientem API KSeF (logowanie i metadane faktur). */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class KsefIntegrationTests {

    private static final String MEDIA_NIP = "7740001454";

    /** Faktury są wspólne dla NIP-u, więc każdy test ma własną firmę. */
    private final String nip = randomNip();
    private final String ownId = nip + "-00001";
    private final String otherId = nip + "-00002";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    KsefCorrectedInvoiceService correctedInvoices;

    @Autowired
    DocumentKsefLinkService ksefLinks;

    @MockitoBean
    KsefClient ksef;

    @Test
    void connectsFetchesAndFiltersPurchaseInvoices() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));

        // Złe hasło do klucza nie zapisuje połączenia.
        connection(owner, storeId, nip, "zle-haslo").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        connection(owner, storeId, "1234567890", "haslo-testowe").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nip").exists());
        connection(owner, storeId, nip, "haslo-testowe")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.nip").value(nip))
                .andExpect(jsonPath("$.connection.certificateSubject").value("JAN TESTOWY (uwierzytelnienie)"))
                .andExpect(jsonPath("$.connection.testOk").value(true));
        String stored = jdbc.queryForObject("select password_encrypted from ksef_connections where store_id = ?::uuid",
                String.class, storeId);
        org.junit.jupiter.api.Assertions.assertFalse(stored.contains("haslo-testowe"));

        send(owner, post(url(storeId, "/settings/internal-ids")), "{\"internalId\":\"1234567890-00001\"}")
                .andExpect(status().isBadRequest());
        send(owner, post(url(storeId, "/settings/internal-ids")), "{\"internalId\":\"" + ownId + "\",\"name\":\"Sklep A\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalIds[0].internalId").value(ownId));

        // Faktura bez IDWew, ze swoim IDWew, z cudzym IDWew i korekta od dostawcy mediów.
        OffsetDateTime stored1 = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("FV/1/2026", "1111111111", "MLECZARNIA", stored1, "Vat", null),
                        invoice("FV/2/2026", "1111111111", "MLECZARNIA", stored1.plusMinutes(1), "Vat", ownId),
                        invoice("FV/3/2026", "1111111111", "MLECZARNIA", stored1.plusMinutes(2), "Vat", otherId),
                        invoice("KOR/4/2026", MEDIA_NIP, "ENERGIA SA", stored1.plusMinutes(3), "Kor", null))));
        syncAndWait(owner, storeId)
                .andExpect(jsonPath("$.syncOk").value(true))
                .andExpect(jsonPath("$.syncMessage").value("Pobrano 4 faktury"));
        // Ponowne pobranie tych samych faktur niczego nie dubluje.
        syncAndWait(owner, storeId).andExpect(jsonPath("$.syncMessage").value("Brak nowych faktur"));

        // Numer pierwszej faktury jest w przyjęciu PZ (zapisany małymi literami) - jest wprowadzona.
        insertDelivery(storeId, "PZ100", "fv/1/2026");

        list(owner, storeId, "").andExpect(jsonPath("$.matchingCount").value(3))
                .andExpect(jsonPath("$.invoices[*].invoiceNumber")
                        .value(containsInAnyOrder("FV/1/2026", "FV/2/2026", "KOR/4/2026")))
                .andExpect(jsonPath("$.grossTotal").value(369.0));
        list(owner, storeId, "&entered=ENTERED").andExpect(jsonPath("$.invoices[0].invoiceNumber").value("FV/1/2026"))
                .andExpect(jsonPath("$.invoices[0].deliveries[0].number").value("PZ100"));
        list(owner, storeId, "&internalId=NONE").andExpect(jsonPath("$.matchingCount").value(2));
        list(owner, storeId, "&internalId=" + ownId).andExpect(jsonPath("$.invoices[0].invoiceNumber").value("FV/2/2026"));
        list(owner, storeId, "&internalId=" + otherId).andExpect(jsonPath("$.matchingCount").value(0));
        list(owner, storeId, "&internalId=OWN").andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.invoices[0].invoiceNumber").value("FV/2/2026"));
        list(owner, storeId, "&kind=CORRECTIONS").andExpect(jsonPath("$.invoices[0].correction").value(true));
        list(owner, storeId, "&query=energia").andExpect(jsonPath("$.matchingCount").value(1));

        // Dostawca mediów pominięty: znika z listy, wraca z "pokaż pominiętych", a usunięcie z listy da się cofnąć.
        String settings = send(owner, post(url(storeId, "/settings/skipped-suppliers")),
                "{\"nip\":\"774-000-14-54\",\"name\":\"ENERGIA SA\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedSuppliers[0].nip").value(MEDIA_NIP))
                .andExpect(jsonPath("$.seenInternalIds[?(@.internalId == '" + otherId + "')].assigned").value(false))
                .andReturn().getResponse().getContentAsString();
        list(owner, storeId, "").andExpect(jsonPath("$.matchingCount").value(2));
        list(owner, storeId, "&showSkipped=true").andExpect(jsonPath("$.matchingCount").value(3))
                .andExpect(jsonPath("$.invoices[?(@.invoiceNumber == 'KOR/4/2026')].supplierSkipped").value(true));
        String skippedId = JsonPath.read(settings, "$.skippedSuppliers[0].id");
        mvc.perform(delete(url(storeId, "/settings/skipped-suppliers/" + skippedId)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.skippedSuppliers.length()").value(0));
        list(owner, storeId, "").andExpect(jsonPath("$.matchingCount").value(3));
        send(owner, post(url(storeId, "/settings/skipped-suppliers/" + skippedId + "/restore")), "")
                .andExpect(jsonPath("$.skippedSuppliers.length()").value(1));

        // Ręczne oznaczenie "wprowadzona" i jego cofnięcie.
        String second = JsonPath.read(list(owner, storeId, "&entered=NOT_ENTERED").andReturn().getResponse()
                .getContentAsString(), "$.invoices[0].id");
        send(owner, put(url(storeId, "/invoices/" + second + "/entered")), "")
                .andExpect(jsonPath("$.entered").value(true))
                .andExpect(jsonPath("$.enteredBy").isNotEmpty());
        list(owner, storeId, "&entered=NOT_ENTERED").andExpect(jsonPath("$.matchingCount").value(0));
        mvc.perform(delete(url(storeId, "/invoices/" + second + "/entered")).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.entered").value(false));

        // PDF faktury powstaje z XML pobranego z KSeF; nazwa pliku z numeru faktury i dostawcy.
        when(ksef.invoiceXml(eq("token"), anyString()))
                .thenReturn(new ClassPathResource("ksef/faktura-fa3.xml").getContentAsByteArray());
        byte[] pdf = mvc.perform(get(url(storeId, "/invoices/" + second + "/pdf")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("Faktura%20FV_2_2026%20MLECZARNIA.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        // Faktura w KSeF się nie zmienia: XML jest zapisany, kolejne pobrania nie zużywają limitu KSeF.
        mvc.perform(get(url(storeId, "/invoices/" + second + "/xml")).cookie(owner)).andExpect(status().isOk());
        verify(ksef, times(1)).invoiceXml(eq("token"), anyString());
        String first = jdbc.queryForObject(
                "select id from ksef_invoices where invoice_number = 'FV/1/2026' and nip = ?", String.class, nip);
        when(ksef.invoiceXml(eq("token"), anyString())).thenReturn("<nie-faktura/>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(url(storeId, "/invoices/" + first + "/pdf")).cookie(owner))
                .andExpect(status().isBadRequest());

        // Faktura z cudzym IDWew jest dla tego sklepu niewidoczna także po id.
        String foreign = jdbc.queryForObject(
                "select id from ksef_invoices where invoice_number = 'FV/3/2026' and nip = ?", String.class, nip);
        send(owner, put(url(storeId, "/invoices/" + foreign + "/entered")), "").andExpect(status().isNotFound());

        mvc.perform(get(url(storeId, "")).cookie(owner))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.syncOk").value(true))
                .andExpect(jsonPath("$.invoiceCount").value(4));
    }

    @Test
    void rateLimitKeepsProgressForNextSync() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, storeId, nip, "haslo-testowe").andExpect(status().isOk());

        OffsetDateTime stored = OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), eq(0))).thenReturn(new KsefInvoiceMetadata.Page(
                true, false, null, List.of(invoice("FV/9/2026", "1111111111", "PIEKARNIA", stored, "Vat", null))));
        when(ksef.purchaseInvoices(anyString(), any(), any(), eq(1)))
                .thenThrow(new KsefClient.KsefException("Przekroczony limit", java.time.Duration.ofSeconds(90)));

        syncAndWait(owner, storeId)
                .andExpect(jsonPath("$.syncMessage").value(startsWith("Limit zapytań KSeF - pobrano 1 fakturę")));
        Instant cursor = jdbc.queryForObject("select sync_cursor from ksef_sync_states where nip = ?",
                java.sql.Timestamp.class, nip).toInstant();
        org.junit.jupiter.api.Assertions.assertEquals(stored.toInstant(), cursor);
    }

    @Test
    void companyInvoicesAreSharedBetweenStores() throws Exception {
        Cookie owner = login();
        String first = createStore(owner);
        String second = createStore(owner, nip);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, first, nip, "haslo-testowe").andExpect(status().isOk());
        send(owner, post(url(first, "/settings/internal-ids")), "{\"internalId\":\"" + ownId + "\"}")
                .andExpect(status().isOk());

        OffsetDateTime stored = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("FV/10/2026", "1111111111", "MLECZARNIA", stored, "Vat", null),
                        invoice("FV/11/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(1), "Vat", ownId),
                        invoice("FV/12/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(2), "Vat", otherId))));
        syncAndWait(owner, first).andExpect(jsonPath("$.syncMessage").value("Pobrano 3 faktury"));

        // Drugi sklep: firma jest już połączona w pierwszym - właściciel podłącza go bez certyfikatu.
        mvc.perform(get(url(second, "/settings")).cookie(owner))
                .andExpect(jsonPath("$.connection").doesNotExist())
                .andExpect(jsonPath("$.companies[?(@.nip == '" + nip + "')]").exists());
        send(owner, post(url(second, "/settings/connection/link")), "{\"nip\":\"" + nip + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.nip").value(nip))
                .andExpect(jsonPath("$.connection.testOk").value(true))
                .andExpect(jsonPath("$.seenInternalIds.length()").value(2));
        send(owner, post(url(second, "/settings/internal-ids")), "{\"internalId\":\"" + otherId + "\"}")
                .andExpect(status().isOk());

        // Bez własnego pobierania widzi faktury firmy: bez IDWew i ze swoim IDWew, wynik ostatniego pobrania też.
        mvc.perform(get(url(second, "")).cookie(owner))
                .andExpect(jsonPath("$.syncMessage").value("Pobrano 3 faktury"))
                .andExpect(jsonPath("$.invoiceCount").value(3));
        list(owner, second, "").andExpect(jsonPath("$.invoices[*].invoiceNumber")
                .value(containsInAnyOrder("FV/10/2026", "FV/12/2026")));
        list(owner, first, "").andExpect(jsonPath("$.invoices[*].invoiceNumber")
                .value(containsInAnyOrder("FV/10/2026", "FV/11/2026")));

        // "Wprowadzona" w jednym sklepie firmy jest wprowadzona we wszystkich, z informacją, gdzie.
        String shared = jdbc.queryForObject(
                "select id from ksef_invoices where invoice_number = 'FV/10/2026' and nip = ?", String.class, nip);
        send(owner, put(url(second, "/invoices/" + shared + "/entered")), "")
                .andExpect(jsonPath("$.entered").value(true));
        list(owner, second, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(1));
        list(owner, first, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.invoices[0].enteredStoreId").value(second));
        // Cofnąć oznaczenie może tylko sklep, który je zrobił.
        send(owner, delete(url(first, "/invoices/" + shared + "/entered")), "")
                .andExpect(jsonPath("$.entered").value(true));
        send(owner, delete(url(second, "/invoices/" + shared + "/entered")), "")
                .andExpect(jsonPath("$.entered").value(false));

        // Przyjęcie z numerem faktury w drugim sklepie: w pierwszym faktura jest wprowadzona, a bon da się pobrać.
        String delivery = insertDocument(second, "PZ", "1234500", "FV/10/2026", "100.00");
        list(owner, first, "&entered=ENTERED")
                .andExpect(jsonPath("$.invoices[0].deliveries[0].id").value(delivery))
                .andExpect(jsonPath("$.invoices[0].deliveries[0].storeId").value(second));
        mvc.perform(get(url(first, "/invoices/" + shared + "/documents/" + delivery + "/delivery-note")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("bon-dostawy-12345.pdf")));
        // Dokumentu, który nie jest przyjęciem tej faktury, nie da się tak pobrać.
        String foreign = insertDocument(second, "PZ", "999900", null, "1.00");
        mvc.perform(get(url(first, "/invoices/" + shared + "/documents/" + foreign + "/delivery-note")).cookie(owner))
                .andExpect(status().isNotFound());

        // Firmy bez połączenia w innym sklepie nie da się podłączyć.
        String unconnectedNip = randomNip();
        send(owner, post(url(createStore(owner, unconnectedNip), "/settings/connection/link")),
                "{\"nip\":\"" + unconnectedNip + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void connectedStoreSharesConnectionWithOtherStoresOfCompany() throws Exception {
        Cookie owner = login();
        String main = createStore(owner);
        String second = createStore(owner, nip);
        String third = createStore(owner, nip);
        String withoutNip = createStore(owner);
        String otherCompany = createStore(owner);
        String otherNip = randomNip();
        when(ksef.authenticate(anyString(), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, main, nip, "haslo-testowe").andExpect(status().isOk());
        connection(owner, otherCompany, otherNip, "haslo-testowe").andExpect(status().isOk());
        // NIP sklepu ma sumę kontrolną; myślniki i spacje są pomijane.
        send(owner, post("/api/settings/stores"), "{\"number\":\"" + ThreadLocalRandom.current().nextLong(1_000_000,
                9_999_999_999L) + "\",\"name\":\"Zły NIP\",\"nip\":\"1234567890\",\"active\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nip").exists());
        // Do podłączenia tylko sklepy z NIP-em tej firmy; sklep innej firmy i sklep bez NIP-u się nie pojawiają.
        mvc.perform(get(url(main, "/settings")).cookie(owner))
                .andExpect(jsonPath("$.storeNip").value(nip))
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + second + "')].connected").value(false))
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + otherCompany + "')]").isEmpty())
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + withoutNip + "')]").isEmpty());

        // Dwa sklepy naraz, bez certyfikatu i bez osobnego logowania do KSeF.
        send(owner, post(url(main, "/settings/connection/share")),
                "{\"storeIds\":[\"" + second + "\",\"" + third + "\",\"" + main + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + second + "')].connected").value(true))
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + third + "')].connected").value(true));
        mvc.perform(get(url(third, "/settings")).cookie(owner))
                .andExpect(jsonPath("$.connection.nip").value(nip))
                .andExpect(jsonPath("$.connection.testOk").value(true));
        // Ponowne podłączenie niczego nie psuje, a sklepu innej firmy nie przełączamy.
        send(owner, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + second + "\"]}")
                .andExpect(status().isOk());
        send(owner, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + otherCompany + "\"]}")
                .andExpect(status().isConflict());
        send(owner, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + withoutNip + "\"]}")
                .andExpect(status().isConflict());
        // Sklep bez NIP-u nie dostaje podpowiedzi firmy i nie podłączy się sam.
        mvc.perform(get(url(withoutNip, "/settings")).cookie(owner)).andExpect(jsonPath("$.companies.length()").value(0));
        send(owner, post(url(withoutNip, "/settings/connection/link")), "{\"nip\":\"" + nip + "\"}")
                .andExpect(status().isBadRequest());
        // Nowe połączenie musi być z firmą z ustawień sklepu, a NIP-u połączonego sklepu nie zmienia się w ustawieniach.
        String fourth = createStore(owner, nip);
        connection(owner, fourth, otherNip, "haslo-testowe").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.nip").exists());
        send(owner, put("/api/settings/stores/" + third),
                "{\"number\":\"" + ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L)
                        + "\",\"name\":\"Sklep\",\"nip\":\"" + otherNip + "\",\"active\":true}")
                .andExpect(status().isConflict());

        // Pracownik nie podłączy sklepu, do którego nie ma dostępu.
        String foreign = createStore(owner);
        Cookie employee = employee(owner, main);
        mvc.perform(get(url(main, "/settings")).cookie(employee)).andExpect(jsonPath("$.otherStores.length()").value(0));
        send(employee, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + foreign + "\"]}")
                .andExpect(status().isNotFound());
        mvc.perform(get(url(foreign, "/settings")).cookie(owner)).andExpect(jsonPath("$.connection").doesNotExist());
    }

    @Test
    void documentsAndInvoicesArePinnedFromBothSides() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, storeId, nip, "haslo-testowe").andExpect(status().isOk());
        OffsetDateTime stored = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("KOR/1/2026", "1111111111", "MLECZARNIA", stored, "Kor", null, new BigDecimal("-50.00")),
                        invoice("FV/40/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(1), "Vat", null,
                                new BigDecimal("98.00")),
                        invoice("FV/41/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(2), "Vat", null,
                                new BigDecimal("105.00")),
                        invoice("FV/42/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(3), "Vat", null,
                                new BigDecimal("300.00")))));
        syncAndWait(owner, storeId).andExpect(jsonPath("$.syncMessage").value("Pobrano 4 faktury"));

        // Korekta od dostawcy na -100 zł: domyślnie faktury z kwotą ±10%, od najbliższej; wyszukiwarka bez zakresu.
        String correction = insertDocument(storeId, "WZ", "WZ1", null, "-100.00");
        String documents = "/api/stores/" + storeId + "/documents/wz/" + correction + "/ksef-invoices";
        mvc.perform(get(documents + "/candidates").cookie(owner))
                .andExpect(jsonPath("$[*].invoiceNumber").value(contains("FV/40/2026", "FV/41/2026")))
                .andExpect(jsonPath("$[0].difference").value(2.0));
        String korekta = JsonPath.read(mvc.perform(get(documents + "/candidates").param("amountRange", "false")
                        .param("query", "kor").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        // Przypięcie z dokumentu: faktura jest wprowadzona, a przy fakturze widać korektę.
        send(owner, post(documents), "{\"invoiceId\":\"" + korekta + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ksefLinkable").value(true))
                .andExpect(jsonPath("$.ksefInvoices[0].invoiceNumber").value("KOR/1/2026"))
                .andExpect(jsonPath("$.ksefInvoices[0].matchedByNumber").value(false));
        list(owner, storeId, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.invoices[0].deliveries[0].type").value("WZ"))
                .andExpect(jsonPath("$.invoices[0].deliveries[0].linked").value(true));
        mvc.perform(get("/api/stores/" + storeId + "/documents/wz").cookie(owner))
                .andExpect(jsonPath("$.documents[0].ksefInvoices[0].invoiceNumber").value("KOR/1/2026"));

        // Przyjęcie z numerem faktury łączy się samo.
        String delivery = insertDocument(storeId, "PZ", "PZ300", "fv/42/2026", "300.00");
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz/" + delivery).cookie(owner))
                .andExpect(jsonPath("$.ksefInvoices[0].invoiceNumber").value("FV/42/2026"))
                .andExpect(jsonPath("$.ksefInvoices[0].matchedByNumber").value(true));

        // Z listy faktur: przyjęcie bez numeru z najbliższą kwotą, przypięcie i odpięcie.
        String fv41 = jdbc.queryForObject("select id from ksef_invoices where invoice_number = 'FV/41/2026' and nip = ?",
                String.class, nip);
        String withoutNumber = insertDocument(storeId, "PZ", "PZ301", null, "104.00");
        mvc.perform(get(url(storeId, "/invoices/" + fv41 + "/documents/candidates")).cookie(owner))
                .andExpect(jsonPath("$[0].number").value("PZ301"))
                .andExpect(jsonPath("$[0].typeSingular").value("Przyjęcie towaru"));
        send(owner, post(url(storeId, "/invoices/" + fv41 + "/documents")), "{\"documentId\":\"" + withoutNumber + "\"}")
                .andExpect(jsonPath("$.entered").value(true))
                .andExpect(jsonPath("$.deliveries[0].number").value("PZ301"));

        // Przyjęcie ma najwyżej jedną fakturę: przypiętą albo dopasowaną po numerze.
        String fv40 = jdbc.queryForObject("select id from ksef_invoices where invoice_number = 'FV/40/2026' and nip = ?",
                String.class, nip);
        mvc.perform(get(url(storeId, "/invoices/" + fv40 + "/documents/candidates")).param("amountRange", "false")
                        .cookie(owner))
                .andExpect(jsonPath("$[*].number").value(everyItem(not(oneOf("PZ300", "PZ301")))));
        send(owner, post("/api/stores/" + storeId + "/documents/pz/" + withoutNumber + "/ksef-invoices"),
                "{\"invoiceId\":\"" + fv40 + "\"}").andExpect(status().isConflict());
        send(owner, post(url(storeId, "/invoices/" + fv40 + "/documents")), "{\"documentId\":\"" + delivery + "\"}")
                .andExpect(status().isConflict());
        send(owner, post(url(storeId, "/invoices/" + fv41 + "/documents")), "{\"documentId\":\"" + withoutNumber + "\"}")
                .andExpect(status().isOk());

        // Kwoty netto: przyjęcie zgodne z fakturą i niezgodne, którego różnicę można potwierdzić i cofnąć.
        String pz = "/api/stores/" + storeId + "/documents/pz/";
        mvc.perform(get(pz + delivery).cookie(owner))
                .andExpect(jsonPath("$.invoiceAmountCheck.matches").value(true));
        // Różnica do 1 zł w obie strony to zgodność.
        mvc.perform(get(pz + withoutNumber).cookie(owner))
                .andExpect(jsonPath("$.invoiceAmountCheck.difference").value(1.0))
                .andExpect(jsonPath("$.invoiceAmountCheck.matches").value(true));
        jdbc.update("update documents set purchase_value = 102.50 where id = ?::uuid", withoutNumber);
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz").param("query", "PZ301").cookie(owner))
                .andExpect(jsonPath("$.documents[0].invoiceAmountCheck.invoiceNet").value(105.0))
                .andExpect(jsonPath("$.documents[0].invoiceAmountCheck.documentValue").value(102.5))
                .andExpect(jsonPath("$.documents[0].invoiceAmountCheck.difference").value(2.5))
                .andExpect(jsonPath("$.documents[0].invoiceAmountCheck.matches").value(false))
                .andExpect(jsonPath("$.documents[0].invoiceAmountCheck.confirmedAt").value(nullValue()));
        send(owner, put(pz + withoutNumber + "/amount-confirmation"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceAmountCheck.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.invoiceAmountCheck.confirmedBy").isNotEmpty());
        send(owner, put(pz + delivery + "/amount-confirmation"), "").andExpect(status().isBadRequest());
        mvc.perform(delete(pz + withoutNumber + "/amount-confirmation").cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.invoiceAmountCheck.confirmedAt").value(nullValue()));
        send(owner, put(pz + withoutNumber + "/amount-confirmation"), "").andExpect(status().isOk());
        // Zmiana kwoty przyjęcia unieważnia potwierdzenie.
        jdbc.update("update documents set purchase_value = 101 where id = ?::uuid", withoutNumber);
        mvc.perform(get(pz + withoutNumber).cookie(owner))
                .andExpect(jsonPath("$.invoiceAmountCheck.difference").value(4.0))
                .andExpect(jsonPath("$.invoiceAmountCheck.confirmedAt").value(nullValue()));

        // Porównanie z XML faktury po EAN: zgodny artykuł, artykuł spoza faktury i pozycja faktury bez kodu.
        insertLine(delivery, 1, "0000000000001", "5900000000012", "MLEKO 3,2% 1L", "100", "200");
        insertLine(delivery, 2, "0000000000002", "5900000000999", "JOGURT", "10", "100");
        when(ksef.invoiceXml(anyString(), anyString())).thenReturn(new ClassPathResource("ksef/faktura-fa3.xml")
                .getContentAsByteArray());
        mvc.perform(get(pz + delivery + "/invoice-comparison").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices[0]").value("FV/42/2026"))
                .andExpect(jsonPath("$.unavailable.length()").value(0))
                .andExpect(jsonPath("$.invoiceNet").value(300.0))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[?(@.status == 'MATCH')].itm8").value(contains("0000000000001")))
                .andExpect(jsonPath("$.rows[?(@.status == 'MISSING_ON_INVOICE')].difference").value(contains(-100.0)))
                .andExpect(jsonPath("$.rows[?(@.status == 'NO_CODE')].invoiceValue").value(contains(100.0)));
        // XML jest już w bazie - drugie porównanie nie pyta KSeF.
        String comparison = mvc.perform(get(pz + delivery + "/invoice-comparison").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        verify(ksef, times(1)).invoiceXml(anyString(), anyString());

        // Ręczne połączenie: pozycja faktury bez kodu to jogurt z przyjęcia - porównują się razem.
        String noCode = JsonPath.<List<String>>read(comparison,
                "$.rows[?(@.status == 'NO_CODE')].invoiceKeys[0]").getFirst();
        String matchUrl = pz + delivery + "/invoice-comparison/matches";
        send(owner, post(matchUrl), "{\"invoiceKeys\":" + JsonPath.parse(List.of(noCode)).jsonString()
                + ",\"itm8s\":[\"0000000009999\"]}")
                .andExpect(status().isBadRequest());
        String match = JsonPath.read(send(owner, post(matchUrl), "{\"invoiceKeys\":"
                        + JsonPath.parse(List.of(noCode)).jsonString() + ",\"itm8s\":[\"0000000000002\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get(pz + delivery + "/invoice-comparison").cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[?(@.itm8 == '0000000000002')].status").value(contains("MATCH")))
                .andExpect(jsonPath("$.rows[?(@.itm8 == '0000000000002')].matchIds[0]").value(contains(match)));
        mvc.perform(delete(matchUrl + "/" + match).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(pz + delivery + "/invoice-comparison").cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(3));
        mvc.perform(post(matchUrl + "/" + match + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(pz + delivery + "/invoice-comparison").cookie(owner))
                .andExpect(jsonPath("$.rows.length()").value(2));

        // Przyjęcie z fakturą z KSeF nie dostaje drugiej spoza KSeF.
        String supplier = JsonPath.read(send(owner, post("/api/stores/" + storeId + "/suppliers"),
                "{\"name\":\"Dostawca bez KSeF\"}").andReturn().getResponse().getContentAsString(), "$.id");
        send(owner, post(pz + delivery + "/manual-invoices"),
                "{\"supplierId\":\"" + supplier + "\",\"invoiceNumber\":\"R/1/26\"}")
                .andExpect(status().isConflict());

        mvc.perform(delete(url(storeId, "/invoices/" + fv41 + "/documents/" + withoutNumber)).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.entered").value(false));

        // Odpięcie z dokumentu zostawia wpis ze znacznikiem.
        mvc.perform(delete(documents + "/" + korekta).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.ksefInvoices.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from document_ksef_invoices where document_id = ?::uuid "
                + "and unlinked_at is not null", Integer.class, correction)).isEqualTo(1);
        // Fakturę przypina się tylko do przyjęć i korekt od dostawcy.
        String liquidation = insertDocument(storeId, "PL", "PL1", null, "100.00");
        mvc.perform(get("/api/stores/" + storeId + "/documents/pl/" + liquidation + "/ksef-invoices/candidates")
                .cookie(owner)).andExpect(status().isBadRequest());
    }

    @Test
    void invoicesAreLinkedAutomaticallyBySupplierAndAmount() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, storeId, nip, "haslo-testowe").andExpect(status().isOk());
        OffsetDateTime stored = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("FV/A", "1111111111", "MLECZARNIA", stored, "Vat", null, new BigDecimal("1000.00")),
                        invoice("FV/B", "1111111111", "MLECZARNIA", stored.plusMinutes(1), "Vat", null,
                                new BigDecimal("1006.00")),
                        invoice("FV/C", "1111111111", "MLECZARNIA", stored.plusMinutes(2), "Vat", null,
                                new BigDecimal("5000.00")),
                        invoice("KOR/A", "1111111111", "MLECZARNIA", stored.plusMinutes(3), "Kor", null,
                                new BigDecimal("1003.00")),
                        invoice("FV/D", "2222222222", "PIEKARNIA", stored.plusMinutes(4), "Vat", null,
                                new BigDecimal("995.00")),
                        invoice("KOR/B", "1111111111", "MLECZARNIA", stored.plusMinutes(5), "Kor", null,
                                new BigDecimal("-50.00")))));
        syncAndWait(owner, storeId);
        jdbc.update("insert into suppliers (store_id, code, name, nip) values (?::uuid, 'S1', 'MLECZARNIA', "
                + "'1111111111'), (?::uuid, 'S2', 'PIEKARNIA', '2222222222')", storeId, storeId);
        String pz1 = insertDocument(storeId, "PZ", "PZ1", null, "1002.00");
        String pz2 = insertDocument(storeId, "PZ", "PZ2", null, "1005.00");
        String pz3 = insertDocument(storeId, "PZ", "PZ3", null, "1000.00");
        String pz4 = insertDocument(storeId, "PZ", "PZ4", null, "2000.00");
        String pz5 = insertDocument(storeId, "PZ", "PZ5", "FV/C", "5000.00");
        jdbc.update("update documents set supplier_code = 'S1' where id in (?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                pz1, pz2, pz4, pz5);
        jdbc.update("update documents set supplier_code = 'S2' where id = ?::uuid", pz3);

        // Przyjęcie, na które nic nie przyszło (0 zł), nie jest na liście ani w liczniku.
        insertDocument(storeId, "PZ", "PZ0", null, "0.00");
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz").cookie(owner))
                .andExpect(jsonPath("$.type.documentCount").value(5))
                .andExpect(jsonPath("$.matchingCount").value(5))
                .andExpect(jsonPath("$.documents[*].number").value(not(org.hamcrest.Matchers.hasItem("PZ0"))));

        // Najpierw najmniejsza różnica: PZ2 bierze FV/B (1 zł), PZ1 zostaje FV/A (2 zł); korekta i PZ z fakturą odpadają.
        String auto = "/api/stores/" + storeId + "/documents/pz/auto-ksef-invoices";
        String proposals = mvc.perform(get(auto).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.documentNumber == 'PZ1')].invoiceNumber").value(contains("FV/A")))
                .andExpect(jsonPath("$[?(@.documentNumber == 'PZ2')].invoiceNumber").value(contains("FV/B")))
                .andExpect(jsonPath("$[?(@.documentNumber == 'PZ3')].invoiceNumber").value(contains("FV/D")))
                .andExpect(jsonPath("$[?(@.documentNumber == 'PZ3')].difference").value(contains(5.0)))
                .andReturn().getResponse().getContentAsString();
        String fvA = JsonPath.<List<String>>read(proposals, "$[?(@.documentNumber == 'PZ1')].invoiceId").getFirst();
        String fvB = JsonPath.<List<String>>read(proposals, "$[?(@.documentNumber == 'PZ2')].invoiceId").getFirst();
        String pairs = "{\"matches\":[{\"documentId\":\"" + pz1 + "\",\"invoiceId\":\"" + fvA + "\"},"
                + "{\"documentId\":\"" + pz2 + "\",\"invoiceId\":\"" + fvB + "\"}]}";

        send(owner, post(auto), pairs).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz/" + pz1).cookie(owner))
                .andExpect(jsonPath("$.ksefInvoices[0].invoiceNumber").value("FV/A"));
        mvc.perform(get(auto).cookie(owner))
                .andExpect(jsonPath("$[*].documentNumber").value(contains("PZ3")));
        // Ponowne przypięcie innej faktury do przyjęcia z fakturą wycofuje całość.
        send(owner, post(auto), "{\"matches\":[{\"documentId\":\"" + pz1 + "\",\"invoiceId\":\"" + fvB + "\"}]}")
                .andExpect(status().isConflict());

        // Cofnięcie odpina (znacznik), przyjęcia znów czekają na fakturę.
        send(owner, post(auto + "/undo"), pairs).andExpect(status().isNoContent());
        mvc.perform(get(auto).cookie(owner)).andExpect(jsonPath("$.length()").value(3));
        assertThat(jdbc.queryForObject("select count(*) from document_ksef_invoices where document_id in "
                + "(?::uuid, ?::uuid) and unlinked_at is not null", Integer.class, pz1, pz2)).isEqualTo(2);

        // Korekty od dostawcy dostają faktury korygujące z kwotą tego samego znaku: WZ1 bierze KOR/B,
        // a WZ2 nie bierze KOR/A (+1003 zł przy korekcie -1003 zł).
        String wz1 = insertDocument(storeId, "WZ", "WZ1", null, "-52.00");
        String wz2 = insertDocument(storeId, "WZ", "WZ2", null, "-1003.00");
        jdbc.update("update documents set supplier_code = 'S1' where id in (?::uuid, ?::uuid)", wz1, wz2);
        String autoWz = "/api/stores/" + storeId + "/documents/wz/auto-ksef-invoices";
        String wzProposals = mvc.perform(get(autoWz).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentNumber").value("WZ1"))
                .andExpect(jsonPath("$[0].invoiceNumber").value("KOR/B"))
                .andExpect(jsonPath("$[0].difference").value(2.0))
                .andReturn().getResponse().getContentAsString();
        String korB = JsonPath.read(wzProposals, "$[0].invoiceId");
        // KOR/B koryguje FV/C, która jest fakturą PZ5 (po numerze), więc WZ1 trafia też do PZ5.
        jdbc.update("insert into ksef_invoice_corrected (invoice_id, invoice_number) values (?::uuid, 'FV/C')", korB);
        String wzPairs = "{\"matches\":[{\"documentId\":\"" + wz1 + "\",\"invoiceId\":\"" + korB + "\"}]}";
        send(owner, post(autoWz), wzPairs)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attached[0].deliveryId").value(pz5))
                .andExpect(jsonPath("$[0].attached[0].correctionId").value(wz1));
        mvc.perform(get(autoWz).cookie(owner)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/stores/" + storeId + "/documents/wz/" + wz1).cookie(owner))
                .andExpect(jsonPath("$.correctionOf.id").value(pz5));

        // Cofnięcie z przyjęciem odpina też korektę od przyjęcia.
        send(owner, post(autoWz + "/undo"), "{\"matches\":[{\"documentId\":\"" + wz1 + "\",\"invoiceId\":\"" + korB
                + "\",\"attached\":[{\"deliveryId\":\"" + pz5 + "\",\"correctionId\":\"" + wz1 + "\"}]}]}")
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/stores/" + storeId + "/documents/wz/" + wz1).cookie(owner))
                .andExpect(jsonPath("$.correctionOf").doesNotExist())
                .andExpect(jsonPath("$.ksefInvoices.length()").value(0));

        // Przypięcie z dokumentu działa tak samo.
        send(owner, post("/api/stores/" + storeId + "/documents/wz/" + wz1 + "/ksef-invoices"),
                "{\"invoiceId\":\"" + korB + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionOf.id").value(pz5));

        // W drugą stronę: WZ2 ma KOR/A, która koryguje FV/A, a przyjęcie z FV/A jeszcze nie ma faktury - nic się nie
        // przypina. Przypięcie FV/A do PZ1 dociąga WZ2 do PZ1.
        String korA = jdbc.queryForObject("select id::text from ksef_invoices where invoice_number = 'KOR/A'",
                String.class);
        jdbc.update("insert into ksef_invoice_corrected (invoice_id, invoice_number) values (?::uuid, 'FV/A')", korA);
        send(owner, post("/api/stores/" + storeId + "/documents/wz/" + wz2 + "/ksef-invoices"),
                "{\"invoiceId\":\"" + korA + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionOf").doesNotExist());
        send(owner, post("/api/stores/" + storeId + "/documents/pz/" + pz1 + "/ksef-invoices"),
                "{\"invoiceId\":\"" + fvA + "\"}").andExpect(status().isOk());
        mvc.perform(get("/api/stores/" + storeId + "/documents/wz/" + wz2).cookie(owner))
                .andExpect(jsonPath("$.correctionOf.id").value(pz1));

        // Korekty odpiętej ręcznie nic nie przypina z powrotem (ani przebieg w tle).
        mvc.perform(delete("/api/stores/" + storeId + "/documents/pz/" + pz1 + "/corrections/" + wz2)
                        .cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        assertThat(ksefLinks.attachPendingCorrections(UUID.fromString(storeId), null)).isEmpty();
        mvc.perform(get("/api/stores/" + storeId + "/documents/wz/" + wz2).cookie(owner))
                .andExpect(jsonPath("$.correctionOf").doesNotExist());

        mvc.perform(get("/api/stores/" + storeId + "/documents/pl/auto-ksef-invoices").cookie(owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storeWithoutInternalIdSeesAllAndFixedPzNumberLinksInvoice() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, storeId, nip, "haslo-testowe").andExpect(status().isOk());
        OffsetDateTime stored = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("FV/20/2026", "1111111111", "MLECZARNIA", stored, "Vat", null),
                        invoice("FV/21/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(1), "Vat", ownId),
                        invoice("FV/22/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(2), "Vat", otherId))));
        syncAndWait(owner, storeId).andExpect(jsonPath("$.syncMessage").value("Pobrano 3 faktury"));

        // Sklep bez IDWew widzi wszystkie faktury firmy, także z IDWew.
        list(owner, storeId, "").andExpect(jsonPath("$.matchingCount").value(3));
        list(owner, storeId, "&internalId=NONE").andExpect(jsonPath("$.matchingCount").value(1));

        // Literówka w numerze faktury w PZ: faktura nie jest wprowadzona, dopóki ktoś nie poprawi numeru.
        insertDelivery(storeId, "PZ200", "FV-20/2026");
        String pz = jdbc.queryForObject("select id from documents where store_id = ?::uuid and number = 'PZ200'",
                String.class, storeId);
        list(owner, storeId, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(0));
        String documentUrl = "/api/stores/" + storeId + "/documents/pz/" + pz + "/supplier-document-number";
        send(owner, put(documentUrl), "{\"supplierDocumentNumber\":\" FV/20/2026 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierDocumentNumber").value("FV/20/2026"))
                .andExpect(jsonPath("$.reportSupplierDocumentNumber").value("FV-20/2026"))
                .andExpect(jsonPath("$.supplierDocumentNumberEditedBy").isNotEmpty());
        list(owner, storeId, "&entered=ENTERED").andExpect(jsonPath("$.invoices[0].invoiceNumber").value("FV/20/2026"));

        // Cofnięcie poprawki przywraca numer z raportu.
        mvc.perform(delete(documentUrl).cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.supplierDocumentNumber").value("FV-20/2026"))
                .andExpect(jsonPath("$.supplierDocumentNumberEditedAt").doesNotExist());
        list(owner, storeId, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(0));
    }

    @Test
    void employeeSeesAndLinksOnlyCompaniesFromOwnStores() throws Exception {
        Cookie owner = login();
        String connected = createStore(owner);
        String foreign = createStore(owner, nip);
        String shared = createStore(owner, nip);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, connected, nip, "haslo-testowe").andExpect(status().isOk());

        // Pracownik innego sklepu nie widzi, że firma jest połączona gdzie indziej, i nie skopiuje jej certyfikatu.
        Cookie stranger = employee(owner, foreign);
        mvc.perform(get(url(foreign, "/settings")).cookie(stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(0));
        send(stranger, post(url(foreign, "/settings/connection/link")), "{\"nip\":\"" + nip + "\"}")
                .andExpect(status().isNotFound());

        // Pracownik obu sklepów (np. kilka firm na jednym koncie) podłącza drugi sklep sam.
        Cookie both = employee(owner, connected, shared);
        mvc.perform(get(url(shared, "/settings")).cookie(both))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].nip").value(nip));
        send(both, post(url(shared, "/settings/connection/link")), "{\"nip\":\"" + nip + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.nip").value(nip));
    }

    @Test
    void ksefCorrectionsAreReadInBackgroundAndPinnedToDeliveryThroughWz() throws Exception {
        Cookie owner = login();
        String storeId = createStore(owner);
        when(ksef.authenticate(eq(nip), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, storeId, nip, "haslo-testowe").andExpect(status().isOk());
        OffsetDateTime stored = OffsetDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneOffset.UTC);
        when(ksef.purchaseInvoices(anyString(), any(), any(), anyInt())).thenReturn(new KsefInvoiceMetadata.Page(
                false, false, OffsetDateTime.now(ZoneOffset.UTC), List.of(
                        invoice("FV/50/2026", "1111111111", "MLECZARNIA", stored, "Vat", null,
                                new BigDecimal("500.00")),
                        invoice("KOR/50/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(1), "Kor", null,
                                new BigDecimal("-40.00")),
                        invoice("KOR/51/2026", "1111111111", "MLECZARNIA", stored.plusMinutes(2), "Kor", null,
                                new BigDecimal("-10.00")),
                        invoice("KOR/SCA/1", "7821977018", "SCA PR POLSKA", stored.plusMinutes(3), "Kor", null,
                                new BigDecimal("-5.00")))));
        syncAndWait(owner, storeId);
        jdbc.update("insert into suppliers (store_id, code, name, nip) values (?::uuid, 'S1', 'MLECZARNIA', "
                + "'1111111111')", storeId);
        String pz = insertDocument(storeId, "PZ", "PZ50", "FV/50/2026", "500.00");
        String wz1 = insertDocument(storeId, "WZ", "WZ1", null, "-41.00");
        String wz2 = insertDocument(storeId, "WZ", "WZ2", null, "-200.00");
        jdbc.update("update documents set supplier_code = 'S1' where id in (?::uuid, ?::uuid, ?::uuid)", pz, wz1, wz2);

        // Przed odczytem XML nie wiadomo, co korygują korekty dostawcy (korekty bazy się nie liczą).
        String path = "/api/stores/" + storeId + "/documents/pz/" + pz + "/ksef-corrections";
        mvc.perform(get(path).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrections.length()").value(0))
                .andExpect(jsonPath("$.pendingCount").value(2));

        when(ksef.invoiceXml(eq("token"), anyString())).thenAnswer(call -> {
            String number = jdbc.queryForObject("select invoice_number from ksef_invoices where ksef_number = ?",
                    String.class, call.<String>getArgument(1));
            return correctionXml(number.equals("KOR/50/2026") ? "fv/50/2026" : "FV/99/2026").getBytes(
                    StandardCharsets.UTF_8);
        });
        assertThat(correctedInvoices.read(nip)).isEqualTo(new KsefCorrectedInvoiceService.Result(0, 2));
        assertThat(correctedInvoices.read(nip)).isEqualTo(new KsefCorrectedInvoiceService.Result(0, 0));
        String scaKsefNumber = jdbc.queryForObject("select ksef_number from ksef_invoices where invoice_number = "
                + "'KOR/SCA/1' and nip = ?", String.class, nip);
        verify(ksef, times(0)).invoiceXml(anyString(), eq(scaKsefNumber));
        list(owner, storeId, "&kind=CORRECTIONS")
                .andExpect(jsonPath("$.invoices[?(@.invoiceNumber == 'KOR/50/2026')].correctedInvoices[0]")
                        .value(contains("fv/50/2026")))
                .andExpect(jsonPath("$.invoices[?(@.invoiceNumber == 'KOR/SCA/1')].correctedInvoices")
                        .value(contains(nullValue())));

        // Korekta faktury przyjęcia (numer bez względu na wielkość liter) z podpowiedzią WZ o zbliżonej kwocie.
        String correction = JsonPath.read(mvc.perform(get(path).cookie(owner))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.corrections[*].invoiceNumber").value(contains("KOR/50/2026")))
                .andExpect(jsonPath("$.corrections[0].correctedInvoiceNumber").value("FV/50/2026"))
                .andExpect(jsonPath("$.corrections[0].documents.length()").value(0))
                .andExpect(jsonPath("$.corrections[0].candidates[*].number").value(contains("WZ1")))
                .andExpect(jsonPath("$.corrections[0].candidates[0].difference").value(1.0))
                .andReturn().getResponse().getContentAsString(), "$.corrections[0].id");

        // Przypięcie: faktura do WZ i WZ do przyjęcia; cofnięcie odpina oba.
        send(owner, post(path + "/" + correction + "/document"), "{\"documentId\":\"" + wz1 + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attached").value(true));
        mvc.perform(get(path).cookie(owner))
                .andExpect(jsonPath("$.corrections[0].documents[0].number").value("WZ1"))
                .andExpect(jsonPath("$.corrections[0].documents[0].attached").value(true))
                .andExpect(jsonPath("$.corrections[0].candidates.length()").value(0));
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz/" + pz).cookie(owner))
                .andExpect(jsonPath("$.corrections[*].number").value(contains("WZ1")))
                .andExpect(jsonPath("$.ksefInvoices[?(@.invoiceNumber == 'KOR/50/2026')].viaCorrection")
                        .value(contains("WZ1")));
        send(owner, delete(path + "/" + correction + "/document/" + wz1).param("detach", "true"), "")
                .andExpect(status().isOk());
        mvc.perform(get(path).cookie(owner))
                .andExpect(jsonPath("$.corrections[0].documents.length()").value(0))
                .andExpect(jsonPath("$.corrections[0].candidates[*].number").value(contains("WZ1")));
        mvc.perform(get("/api/stores/" + storeId + "/documents/pz/" + pz).cookie(owner))
                .andExpect(jsonPath("$.corrections.length()").value(0));

        // Korekty, która nie dotyczy faktury przyjęcia, nie da się przez nie przypiąć.
        String other = jdbc.queryForObject("select id from ksef_invoices where invoice_number = 'KOR/51/2026' "
                + "and nip = ?", String.class, nip);
        send(owner, post(path + "/" + other + "/document"), "{\"documentId\":\"" + wz2 + "\"}")
                .andExpect(status().isNotFound());
    }

    /** Minimalna faktura korygująca FA(3) z jedną fakturą korygowaną (spoza KSeF, bez numeru KSeF). */
    private static String correctionXml(String correctedNumber) {
        return """
                <Faktura xmlns="http://crd.gov.pl/wzor/2025/06/25/13775/">
                  <Naglowek><KodFormularza kodSystemowy="FA (3)">FA</KodFormularza></Naglowek>
                  <Fa>
                    <RodzajFaktury>KOR</RodzajFaktury>
                    <DaneFaKorygowanej>
                      <DataWystFaKorygowanej>2026-10-10</DataWystFaKorygowanej>
                      <NrFaKorygowanej>%s</NrFaKorygowanej>
                      <NrKSeFN>1</NrKSeFN>
                    </DaneFaKorygowanej>
                  </Fa>
                </Faktura>""".formatted(correctedNumber);
    }

    /** Losowy NIP z poprawną cyfrą kontrolną. */
    private static String randomNip() {
        int[] weights = {6, 5, 7, 2, 3, 4, 5, 6, 7};
        while (true) {
            String digits = String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000L, 1_000_000_000L));
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                sum += weights[i] * (digits.charAt(i) - '0');
            }
            if (sum % 11 < 10) {
                return digits + sum % 11;
            }
        }
    }

    private KsefInvoiceMetadata invoice(String number, String sellerNip, String sellerName,
            OffsetDateTime storedAt, String type, String internalId) {
        return invoice(number, sellerNip, sellerName, storedAt, type, internalId, new BigDecimal("100.00"));
    }

    private KsefInvoiceMetadata invoice(String number, String sellerNip, String sellerName,
            OffsetDateTime storedAt, String type, String internalId, BigDecimal net) {
        List<KsefInvoiceMetadata.ThirdSubject> third = internalId == null ? List.of()
                : List.of(new KsefInvoiceMetadata.ThirdSubject(
                        new KsefInvoiceMetadata.Identifier("InternalId", internalId), "Sklep " + internalId, 2));
        return new KsefInvoiceMetadata(sellerNip + "-20261020-" + Integer.toHexString(number.hashCode()).toUpperCase()
                + "-AB", number, LocalDate.of(2026, 10, 19), storedAt.minusMinutes(1), storedAt,
                new KsefInvoiceMetadata.Seller(sellerNip, sellerName),
                new KsefInvoiceMetadata.Buyer(new KsefInvoiceMetadata.Identifier("Nip", nip), "TRYTOMA"),
                net, net.multiply(new BigDecimal("1.23")), net.multiply(new BigDecimal("0.23")), "PLN", type, false,
                third);
    }

    private ResultActions connection(Cookie owner, String storeId, String nip, String password) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, url(storeId, "/settings/connection"))
                .file(new MockMultipartFile("certificate", "test.crt", "application/x-x509-ca-cert", fixture("test.crt")))
                .file(new MockMultipartFile("privateKey", "test.key", "application/octet-stream", fixture("test.key")))
                .param("nip", nip)
                .param("password", password)
                .cookie(owner).with(csrf()));
    }

    /** Pobieranie idzie w tle: zlecenie (202), potem nagłówek, aż pobieranie się skończy. */
    private ResultActions syncAndWait(Cookie owner, String storeId) throws Exception {
        send(owner, post(url(storeId, "/sync")), "").andExpect(status().isAccepted());
        for (int attempt = 0; attempt < 200; attempt++) {
            ResultActions overview = mvc.perform(get(url(storeId, "")).cookie(owner)).andExpect(status().isOk());
            if (!(Boolean) JsonPath.read(overview.andReturn().getResponse().getContentAsString(), "$.syncRunning")) {
                return overview;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Pobieranie z KSeF nie skończyło się w 10 s");
    }

    private ResultActions list(Cookie owner, String storeId, String params) throws Exception {
        return mvc.perform(get(url(storeId, "/invoices?page=0" + params)).cookie(owner)).andExpect(status().isOk());
    }

    private static byte[] fixture(String name) throws IOException {
        return new ClassPathResource("ksef/" + name).getContentAsString(StandardCharsets.US_ASCII)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private ResultActions send(Cookie owner, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String url(String storeId, String path) {
        return "/api/stores/" + storeId + "/ksef" + path;
    }

    /** Dokument sklepu z wartością zakupu; zwraca id. */
    private String insertDocument(String storeId, String type, String number, String supplierDocumentNumber,
            String purchaseValue) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, supplier_name, supplier_document_number,
                    report_supplier_document_number, line_count, purchase_value, sale_value_gross, first_import_id,
                    last_import_id)
                values (?::uuid, ?, ?, ?, 'MLECZARNIA', ?, ?, 1, ?::numeric, 0, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, LocalDateTime.of(2026, 10, 20, 0, 0), supplierDocumentNumber,
                supplierDocumentNumber, purchaseValue, importId, importId);
    }

    private void insertLine(String documentId, int lineNumber, String itm8, String ean, String name, String quantity,
            String purchaseValue) {
        jdbc.update("""
                insert into document_lines (id, document_id, line_number, itm8, ean, name, quantity, purchase_value)
                values (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?::numeric, ?::numeric)""",
                documentId, lineNumber, itm8, ean, name, quantity, purchaseValue);
    }

    private void insertDelivery(String storeId, String number, String supplierDocumentNumber) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into documents (store_id, type, number, document_date, supplier_document_number,
                    report_supplier_document_number, line_count, purchase_value, sale_value_gross, first_import_id,
                    last_import_id)
                values (?::uuid, 'PZ', ?, ?, ?, ?, 1, 0, 0, ?::uuid, ?::uuid)""",
                storeId, number, LocalDateTime.of(2026, 10, 20, 0, 0), supplierDocumentNumber, supplierDocumentNumber,
                importId, importId);
    }

    private String createStore(Cookie owner) throws Exception {
        return createStore(owner, null);
    }

    /** Sklep z NIP-em firmy w ustawieniach ({@code null} = bez NIP-u). */
    private String createStore(Cookie owner, String storeNip) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true"
                        + (storeNip != null ? ",\"nip\":\"" + storeNip + "\"" : "") + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    /** Pracownik z uprawnieniem KSEF_INVOICES:EDIT przypisany do podanych sklepów. */
    private Cookie employee(Cookie owner, String... storeIds) throws Exception {
        String roleJson = send(owner, post("/api/settings/roles"),
                "{\"name\":\"KSeF " + UUID.randomUUID() + "\",\"permissions\":{\"KSEF_INVOICES\":\"EDIT\"}}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "ksef" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        for (String storeId : storeIds) {
            jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId, storeId);
        }
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
