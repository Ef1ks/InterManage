package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class StorePartnerTransferIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void partnersInviteAcceptAndRemoveWithRestore() throws Exception {
        Cookie owner = login();
        String first = createStore(owner);
        String second = createStore(owner);

        String invited = send(owner, post(partnersUrl(first)), "{\"storeId\":\"" + second + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partners.length()").value(1))
                .andExpect(jsonPath("$.partners[0].store.id").value(second))
                .andExpect(jsonPath("$.partners[0].sent").value(true))
                .andExpect(jsonPath("$.partners[0].acceptedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.invitableStores[*].id", Matchers.not(Matchers.hasItem(second))))
                .andReturn().getResponse().getContentAsString();
        String partnerId = JsonPath.read(invited, "$.partners[0].id");

        // Para sklepów ma jedno zaproszenie, niezależnie od tego, kto zaprasza.
        send(owner, post(partnersUrl(second)), "{\"storeId\":\"" + first + "\"}").andExpect(status().isConflict());
        // Zaproszenie akceptuje tylko sklep zaproszony.
        send(owner, post(partnersUrl(first) + "/" + partnerId + "/accept"), "{}").andExpect(status().isForbidden());

        mvc.perform(get(partnersUrl(second)).cookie(owner))
                .andExpect(jsonPath("$.partners[0].sent").value(false))
                .andExpect(jsonPath("$.partners[0].store.id").value(first));
        send(owner, post(partnersUrl(second) + "/" + partnerId + "/accept"), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partners[0].acceptedAt").isNotEmpty());

        // Zakończenie znajomości (z drugiej strony) to znacznik, który da się cofnąć.
        send(owner, delete(partnersUrl(first) + "/" + partnerId), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partners.length()").value(0));
        send(owner, post(partnersUrl(second) + "/" + partnerId + "/restore"), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partners[0].acceptedAt").isNotEmpty());
    }

    @Test
    void transferIsShownToPartnerStoreUntilEntered() throws Exception {
        Cookie owner = login();
        String sender = createStore(owner);
        String receiver = createStore(owner);
        String stranger = createStore(owner);
        String transfer = insertTransfer(sender, "MM0000120261022");
        String delivery = insertDocument(sender, "PZ", "999300");

        // Bez zaakceptowanej znajomości nie da się wskazać sklepu.
        setTarget(owner, sender, transfer, receiver).andExpect(status().isBadRequest());
        String invited = send(owner, post(partnersUrl(sender)), "{\"storeId\":\"" + receiver + "\"}")
                .andReturn().getResponse().getContentAsString();
        String partnerId = JsonPath.read(invited, "$.partners[0].id");
        setTarget(owner, sender, transfer, receiver).andExpect(status().isBadRequest());
        send(owner, post(partnersUrl(receiver) + "/" + partnerId + "/accept"), "{}").andExpect(status().isOk());

        setTarget(owner, sender, transfer, stranger).andExpect(status().isBadRequest());
        // Dla innego sklepu wystawia się tylko ruch międzymagazynowy.
        send(owner, put(documentsUrl(sender) + "/pz/" + delivery + "/transfer-target"),
                "{\"storeId\":\"" + receiver + "\"}").andExpect(status().isBadRequest());

        setTarget(owner, sender, transfer, receiver)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferTargetAllowed").value(true))
                .andExpect(jsonPath("$.transferTarget.store.id").value(receiver));
        mvc.perform(get(documentsUrl(sender) + "/mm").cookie(owner))
                .andExpect(jsonPath("$.documents[0].transferTarget.store.id").value(receiver));

        String incoming = mvc.perform(get(incomingUrl(receiver)).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.pending[0].fromStore.id").value(sender))
                .andExpect(jsonPath("$.pending[0].number").value("MM0000120261022"))
                .andExpect(jsonPath("$.entered.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String targetId = JsonPath.read(incoming, "$.pending[0].id");
        mvc.perform(get(incomingUrl(receiver) + "/count").cookie(owner)).andExpect(jsonPath("$").value(1));
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].name").value("SAŁATKA"));
        // Inny sklep nie widzi cudzego dokumentu.
        mvc.perform(get(incomingUrl(stranger) + "/" + targetId).cookie(owner)).andExpect(status().isNotFound());

        send(owner, put(incomingUrl(receiver) + "/" + targetId + "/entered"), "{\"entered\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enteredAt").isNotEmpty());
        // Samo oznaczenie niczego nie potwierdza: dokument czeka na przypięcie dokumentu przyjęcia.
        mvc.perform(get(incomingUrl(receiver)).cookie(owner))
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.entered.length()").value(0));
        mvc.perform(get(documentsUrl(sender) + "/mm/" + transfer).cookie(owner))
                .andExpect(jsonPath("$.transferTarget.enteredAt").isNotEmpty())
                .andExpect(jsonPath("$.transferTarget.receipt").value(Matchers.nullValue()));

        // Cofnięcie przez sklep wysyłający: dokument znika u odbiorcy, "Przywróć" go oddaje.
        send(owner, delete(documentsUrl(sender) + "/mm/" + transfer + "/transfer-target"), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferTarget").value(Matchers.nullValue()));
        mvc.perform(get(incomingUrl(receiver)).cookie(owner)).andExpect(jsonPath("$.pending.length()").value(0));
        send(owner, post(documentsUrl(sender) + "/mm/" + transfer + "/transfer-target/restore"), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferTarget.store.id").value(receiver));
    }

    @Test
    void receiptDocumentEndsWaitingAndReceiverSeesTransferAfterCorrections() throws Exception {
        Cookie owner = login();
        String sender = createStore(owner);
        String receiver = createStore(owner);
        String invited = send(owner, post(partnersUrl(sender)), "{\"storeId\":\"" + receiver + "\"}")
                .andReturn().getResponse().getContentAsString();
        send(owner, post(partnersUrl(receiver) + "/" + JsonPath.read(invited, "$.partners[0].id") + "/accept"),
                "{}").andExpect(status().isOk());

        // Wydanie: ilość na plus, zakup na minus. Poprawa odejmuje 8 szt. pierwszego artykułu i cały drugi.
        String transfer = insertValued(sender, "MM0000120261023", "-1202",
                line("0000000000011", "5900000000017", "OLEJ", 48, -1152),
                line("0000000000012", "5900000000024", "RECZNIK", 10, -50));
        String fix = insertValued(sender, "MM0000220261023", "242",
                line("0000000000011", "5900000000017", "OLEJ", -8, 192),
                line("0000000000012", "5900000000024", "RECZNIK", -10, 50));
        setTarget(owner, sender, transfer, receiver).andExpect(status().isOk());
        send(owner, post(documentsUrl(sender) + "/mm/" + fix + "/corrections"),
                "{\"documentId\":\"" + transfer + "\"}").andExpect(status().isOk());

        String list = mvc.perform(get(incomingUrl(receiver)).cookie(owner))
                .andExpect(jsonPath("$.pending[0].purchaseValue").value(-960))
                .andExpect(jsonPath("$.pending[0].lineCount").value(1))
                .andExpect(jsonPath("$.pending[0].corrections[0]").value("MM0000220261023"))
                .andReturn().getResponse().getContentAsString();
        String targetId = JsonPath.read(list, "$.pending[0].id");
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId).cookie(owner))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(40));
        assertThat(mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/export/proto").cookie(owner))
                .andReturn().getResponse().getContentAsString()).isEqualTo("5900000000017000040000\r\n");
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/print/pdf").cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(documentsUrl(sender) + "/mm/" + transfer + "/print/xlsx").cookie(owner))
                .andExpect(status().isOk());

        // Przyjęcie w sklepie docelowym: dokument na plus; dokument na minus albo cudzy nie potwierdza niczego.
        String receipt = insertValued(receiver, "MM0000120261024", "960",
                line("0000000000011", "5900000000017", "OLEJ", -40, 960));
        String issue = insertValued(receiver, "MM0000220261024", "-5",
                line("0000000000013", "5900000000031", "SOL", 1, -5));
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/receipt/candidates").cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("MM0000120261024"))
                .andExpect(jsonPath("$[0].commonItems").value(1));
        String receiptUrl = incomingUrl(receiver) + "/" + targetId + "/receipt";
        send(owner, post(receiptUrl), "{\"documentId\":\"" + issue + "\"}").andExpect(status().isBadRequest());
        send(owner, post(receiptUrl), "{\"documentId\":\"" + fix + "\"}").andExpect(status().isNotFound());

        send(owner, post(receiptUrl), "{\"documentId\":\"" + receipt + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.number").value("MM0000120261024"))
                .andExpect(jsonPath("$.enteredAt").isNotEmpty());
        mvc.perform(get(incomingUrl(receiver) + "/count").cookie(owner)).andExpect(jsonPath("$").value(0));
        mvc.perform(get(incomingUrl(receiver)).cookie(owner))
                .andExpect(jsonPath("$.pending.length()").value(0))
                .andExpect(jsonPath("$.entered[0].receipt.number").value("MM0000120261024"));
        mvc.perform(get(documentsUrl(sender) + "/mm/" + transfer).cookie(owner))
                .andExpect(jsonPath("$.transferTarget.receipt.number").value("MM0000120261024"));
        mvc.perform(get(documentsUrl(sender) + "/mm").cookie(owner))
                .andExpect(jsonPath("$.documents[?(@.number == 'MM0000120261023')].transferTarget.receipt.number")
                        .value("MM0000120261024"));
        mvc.perform(get(documentsUrl(receiver) + "/mm/" + receipt).cookie(owner))
                .andExpect(jsonPath("$.transferReceiptOf.number").value("MM0000120261023"));

        // Dokument przyjęcia nie jest poprawą własnego ruchu, a odpięcie przywraca czekanie.
        send(owner, post(documentsUrl(receiver) + "/mm/" + issue + "/corrections"),
                "{\"documentId\":\"" + receipt + "\"}").andExpect(status().isConflict());
        send(owner, delete(receiptUrl), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value(Matchers.nullValue()));
        mvc.perform(get(incomingUrl(receiver) + "/count").cookie(owner)).andExpect(jsonPath("$").value(1));
    }

    @Test
    void receivedQuantitiesMarkEnteredAndGoToProtoFile() throws Exception {
        Cookie owner = login();
        String sender = createStore(owner);
        String receiver = createStore(owner);
        String transfer = insertTransfer(sender, "MM0000220261022");
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, ean, name, quantity, purchase_value)
                values (?::uuid, 2, '0000000000002', '5900000000017', 'PIEROGI', -3, -30)""", transfer);
        String invited = send(owner, post(partnersUrl(sender)), "{\"storeId\":\"" + receiver + "\"}")
                .andReturn().getResponse().getContentAsString();
        send(owner, post(partnersUrl(receiver) + "/" + JsonPath.read(invited, "$.partners[0].id") + "/accept"),
                "{}").andExpect(status().isOk());
        setTarget(owner, sender, transfer, receiver).andExpect(status().isOk());
        String targetId = JsonPath.read(mvc.perform(get(incomingUrl(receiver)).cookie(owner))
                .andReturn().getResponse().getContentAsString(), "$.pending[0].id");
        String detail = mvc.perform(get(incomingUrl(receiver) + "/" + targetId).cookie(owner))
                .andExpect(jsonPath("$.received.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String pierogi = JsonPath.read(detail, "$.lines[?(@.name == 'PIEROGI')].id").toString()
                .replaceAll("[\\[\\]\"]", "");

        // Bez wpisanych ilości plik ma ilości z dokumentu (bez minusa wydania); linia bez EAN-u jest pominięta.
        String proto = mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/export/proto").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header()
                        .string("Content-Disposition", Matchers.containsString(".Traite0")))
                .andReturn().getResponse().getContentAsString();
        assertThat(proto).isEqualTo("5900000000017000003000\r\n");

        send(owner, post(incomingUrl(receiver) + "/" + targetId + "/receive"),
                "{\"items\":[{\"lineId\":\"" + transfer + "\",\"quantity\":1}]}")
                .andExpect(status().isBadRequest());
        send(owner, post(incomingUrl(receiver) + "/" + targetId + "/receive"),
                "{\"items\":[{\"lineId\":\"" + pierogi + "\",\"quantity\":-1}]}")
                .andExpect(status().isBadRequest());
        send(owner, post(incomingUrl(receiver) + "/" + targetId + "/receive"),
                "{\"items\":[{\"lineId\":\"" + pierogi + "\",\"quantity\":2.5}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enteredAt").isNotEmpty())
                .andExpect(jsonPath("$.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.received[0].quantity").value(2.5));
        // Poprawka nadpisuje poprzednią ilość.
        send(owner, post(incomingUrl(receiver) + "/" + targetId + "/receive"),
                "{\"items\":[{\"lineId\":\"" + pierogi + "\",\"quantity\":2.75}]}")
                .andExpect(jsonPath("$.received.length()").value(1))
                .andExpect(jsonPath("$.received[0].quantity").value(2.75));

        proto = mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/export/proto").cookie(owner))
                .andReturn().getResponse().getContentAsString();
        assertThat(proto).isEqualTo("5900000000017000002750\r\n");
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/export/xlsx").cookie(owner))
                .andExpect(status().isOk());
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/export/xml").cookie(owner))
                .andExpect(status().isNotFound());

        // Wydruk przyjęcia: ilości, które przyjechały, z ilością z dokumentu przy zmienionej linii.
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/print/pdf").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("przyjecie-miedzymagazynowe-")));
        byte[] excel = mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/print/xlsx").cookie(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(excel))) {
            while (!zip.getNextEntry().getName().equals("xl/worksheets/sheet1.xml")) {
                zip.closeEntry();
            }
            assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("PRZYJĘCIE MIĘDZYMAGAZYNOWE")
                    .contains("2.75</v>")
                    .contains("w dokumencie: 3")
                    .contains("Podsumowanie według nomenklatury");
        }
        mvc.perform(get(incomingUrl(receiver) + "/" + targetId + "/print/xml").cookie(owner))
                .andExpect(status().isNotFound());
        // Dokument cudzego sklepu: tylko sklep docelowy widzi przyjęcie.
        mvc.perform(get(incomingUrl(sender) + "/" + targetId + "/print/pdf").cookie(owner))
                .andExpect(status().isNotFound());
    }

    private ResultActions setTarget(Cookie owner, String storeId, String documentId, String targetStoreId)
            throws Exception {
        return send(owner, put(documentsUrl(storeId) + "/mm/" + documentId + "/transfer-target"),
                "{\"storeId\":\"" + targetStoreId + "\"}");
    }

    private ResultActions send(Cookie owner, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String partnersUrl(String storeId) {
        return "/api/stores/" + storeId + "/partners";
    }

    private static String documentsUrl(String storeId) {
        return "/api/stores/" + storeId + "/documents";
    }

    private static String incomingUrl(String storeId) {
        return "/api/stores/" + storeId + "/incoming-transfers";
    }

    /** Ruch międzymagazynowy z jedną linią, tak jakby przyszedł z raportu Wejścia/Wyjścia. */
    private String insertTransfer(String storeId, String number) {
        String documentId = insertDocument(storeId, "MM", number);
        jdbc.update("""
                insert into document_lines (document_id, line_number, itm8, name, quantity, purchase_value)
                values (?::uuid, 1, '0000000000001', 'SAŁATKA', -2, -20)""", documentId);
        return documentId;
    }

    private record Line(String itm8, String ean, String name, int quantity, int purchaseValue) {
    }

    private static Line line(String itm8, String ean, String name, int quantity, int purchaseValue) {
        return new Line(itm8, ean, name, quantity, purchaseValue);
    }

    /** Ruch międzymagazynowy z podanymi liniami i wartością zakupu. */
    private String insertValued(String storeId, String number, String purchaseValue, Line... lines) {
        String documentId = insertDocument(storeId, "MM", number);
        jdbc.update("update documents set purchase_value = ?::numeric, line_count = ? where id = ?::uuid",
                purchaseValue, lines.length, documentId);
        int lineNumber = 0;
        for (Line line : lines) {
            jdbc.update("""
                    insert into document_lines (document_id, line_number, itm8, ean, name, quantity, purchase_value)
                    values (?::uuid, ?, ?, ?, ?, ?, ?)""", documentId, ++lineNumber, line.itm8(), line.ean(),
                    line.name(), line.quantity(), line.purchaseValue());
        }
        return documentId;
    }

    private String insertDocument(String storeId, String type, String number) {
        String importId = jdbc.queryForObject("""
                insert into document_imports (store_id, file_name, total_lines, saved_lines, created_count,
                    updated_count, skipped_lines)
                values (?::uuid, 'Zeszyt2.xlsx', 1, 1, 1, 0, 0) returning id""", String.class, storeId);
        return jdbc.queryForObject("""
                insert into documents (store_id, type, number, document_date, line_count, purchase_value,
                    sale_value_gross, first_import_id, last_import_id)
                values (?::uuid, ?, ?, ?, 1, -20, 30, ?::uuid, ?::uuid) returning id""",
                String.class, storeId, type, number, LocalDateTime.of(2026, 10, 22, 12, 0), importId, importId);
    }

    private String createStore(Cookie owner) throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = send(owner, post("/api/settings/stores"),
                "{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}")
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
