package com.intermanage.backend;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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

import com.intermanage.backend.component.KsefClient;
import com.intermanage.backend.dto.KsefInvoiceMetadata;
import com.intermanage.backend.security.JwtService;
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
        String second = createStore(owner);
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

        // "Wprowadzona" jest decyzją sklepu.
        String shared = jdbc.queryForObject(
                "select id from ksef_invoices where invoice_number = 'FV/10/2026' and nip = ?", String.class, nip);
        send(owner, put(url(second, "/invoices/" + shared + "/entered")), "")
                .andExpect(jsonPath("$.entered").value(true));
        list(owner, second, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(1));
        list(owner, first, "&entered=ENTERED").andExpect(jsonPath("$.matchingCount").value(0));

        // Firmy bez połączenia w innym sklepie nie da się podłączyć.
        send(owner, post(url(createStore(owner), "/settings/connection/link")), "{\"nip\":\"" + randomNip() + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void connectedStoreSharesConnectionWithOtherStoresOfCompany() throws Exception {
        Cookie owner = login();
        String main = createStore(owner);
        String second = createStore(owner);
        String third = createStore(owner);
        String otherCompany = createStore(owner);
        String otherNip = randomNip();
        when(ksef.authenticate(anyString(), any(), any()))
                .thenReturn(new KsefClient.AccessToken("token", Instant.now().plus(1, ChronoUnit.HOURS)));
        connection(owner, main, nip, "haslo-testowe").andExpect(status().isOk());
        connection(owner, otherCompany, otherNip, "haslo-testowe").andExpect(status().isOk());
        mvc.perform(get(url(main, "/settings")).cookie(owner))
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + second + "')]").exists())
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + otherCompany + "')].nip").value(otherNip));

        // Dwa sklepy naraz, bez certyfikatu i bez osobnego logowania do KSeF.
        send(owner, post(url(main, "/settings/connection/share")),
                "{\"storeIds\":[\"" + second + "\",\"" + third + "\",\"" + main + "\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + second + "')].nip").value(nip))
                .andExpect(jsonPath("$.otherStores[?(@.id == '" + third + "')].nip").value(nip));
        mvc.perform(get(url(third, "/settings")).cookie(owner))
                .andExpect(jsonPath("$.connection.nip").value(nip))
                .andExpect(jsonPath("$.connection.testOk").value(true));
        // Ponowne podłączenie niczego nie psuje, a sklepu innej firmy nie przełączamy.
        send(owner, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + second + "\"]}")
                .andExpect(status().isOk());
        send(owner, post(url(main, "/settings/connection/share")), "{\"storeIds\":[\"" + otherCompany + "\"]}")
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
        String foreign = createStore(owner);
        String shared = createStore(owner);
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
        List<KsefInvoiceMetadata.ThirdSubject> third = internalId == null ? List.of()
                : List.of(new KsefInvoiceMetadata.ThirdSubject(
                        new KsefInvoiceMetadata.Identifier("InternalId", internalId), "Sklep " + internalId, 2));
        return new KsefInvoiceMetadata(sellerNip + "-20261020-" + Integer.toHexString(number.hashCode()).toUpperCase()
                + "-AB", number, LocalDate.of(2026, 10, 19), storedAt.minusMinutes(1), storedAt,
                new KsefInvoiceMetadata.Seller(sellerNip, sellerName),
                new KsefInvoiceMetadata.Buyer(new KsefInvoiceMetadata.Identifier("Nip", nip), "TRYTOMA"),
                new BigDecimal("100.00"), new BigDecimal("123.00"), new BigDecimal("23.00"), "PLN", type, false, third);
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
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
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
