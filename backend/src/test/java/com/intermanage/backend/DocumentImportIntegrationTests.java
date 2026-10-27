package com.intermanage.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.intermanage.backend.auth.security.JwtService;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DocumentImportIntegrationTests {

    /** Nagłówek jak w raporcie "Statystyka: Wejścia/Wyjścia" (z francuskimi znakami). */
    private static final String[] HEADER = {"Code nomenclature", "Libellé nomenclature", "Code fournisseur",
            "Libellé fournisseur", "Date Document", "Type Document", "Numéro document interne",
            "Numéro document fournisseur", "N° ligne", "ITM8", "EAN13", "Libellé article", "Marque", "Unité Mesure",
            "Quantité", "Prix achat FIFO", "Prix Vente actuel", "Prix vente document", "Valeur FIFO",
            "Valeur Vente TTC", "Valeur Vente HT", "TVA", "Valeur TVA", "Marge en valeur", "Marge en %"};
    private static final String STORE_NUMBER = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    private static String storeId;

    @Autowired
    MockMvc mvc;

    @Test
    void importStoresAllDocumentTypesAndMergesLaterReports() throws Exception {
        Cookie owner = login();
        String a = docNumber();
        String b = docNumber();
        String liquidation = "PL" + docNumber();
        String pressReturn = "ZW" + docNumber();

        upload(owner, report(STORE_NUMBER,
                line("PZ", a, "", 1, "3", "4.49", "13.47"),
                line("PZ", a, "", 2, "0", "2.99", "0"),
                line("PZ", b, "F/016441/26", 1, "12", "1.5", "18"),
                // Likwidacja: każda linia od innego dostawcy.
                line("PL", liquidation, "0", 36, "1", "-3.85", "-3.85"),
                withSupplier(line("PL", liquidation, "0", 37, "2", "-1", "-2"), "108", "RADIX-BIS Sp. z o.o."),
                // Zwrot prasy: wszystkie linie z numerem 1, różne ITM8.
                line("ZW", pressReturn, "0", 1, "2", "-3.09", "-6.18"),
                withItm8(line("ZW", pressReturn, "0", 1, "5", "-3.42", "-17.1"), "0000099007468"),
                line("XX", "XX0000120261022", "0", 1, "1", "1", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalLines").value(8))
                .andExpect(jsonPath("$.savedLines").value(7))
                .andExpect(jsonPath("$.createdCount").value(4))
                .andExpect(jsonPath("$.skippedLines").value(1))
                .andExpect(jsonPath("$.skippedTypes").value("XX"))
                .andExpect(jsonPath("$.periodFrom").value("2026-10-20"));

        mvc.perform(get(url("")).cookie(owner))
                .andExpect(jsonPath("$.lastReportGeneratedAt").value("2026-10-24T16:10:50"))
                .andExpect(jsonPath("$.types.length()").value(9))
                .andExpect(jsonPath("$.types[0].code").value("PZ"))
                .andExpect(jsonPath("$.types[0].label").value("Przyjęcia towaru"))
                .andExpect(jsonPath("$.types[0].documentCount").value(2))
                .andExpect(jsonPath("$.types[?(@.code == 'PL')].documentCount").value(1))
                .andExpect(jsonPath("$.types[?(@.code == 'RWREP')].documentCount").value(0));
        mvc.perform(get(url("/pl")).cookie(owner))
                .andExpect(jsonPath("$.type.label").value("Likwidacje"))
                .andExpect(jsonPath("$.documents[0].lineCount").value(2))
                .andExpect(jsonPath("$.documents[0].supplierName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(url("/zw")).cookie(owner)).andExpect(jsonPath("$.documents[0].lineCount").value(2));
        mvc.perform(get(url("/xx")).cookie(owner)).andExpect(status().isNotFound());

        // Wyszukiwanie, sortowanie i sumy w bazie: numer faktury dostawcy znajduje tylko dokument B, "Razem" liczy się z wyników.
        mvc.perform(get(url("/pz")).param("query", "f/016441").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.documents[0].number").value(b))
                .andExpect(jsonPath("$.purchaseTotal").value(18))
                .andExpect(jsonPath("$.type.documentCount").value(2));
        mvc.perform(get(url("/pz")).param("sort", "purchase").param("direction", "desc").cookie(owner))
                .andExpect(jsonPath("$.documents[0].number").value(b))
                .andExpect(jsonPath("$.hasMore").value(false));

        String docA = "$.documents[?(@.number == '" + a + "')]";
        mvc.perform(get(url("/pz")).cookie(owner))
                .andExpect(jsonPath("$.lastReportGeneratedAt").value("2026-10-24T16:10:50"))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath(docA + ".lineCount").value(2))
                .andExpect(jsonPath(docA + ".purchaseValue").value(13.47))
                .andExpect(jsonPath(docA + ".supplierName").value("SCAPR POLSKA SP.Z.O.O."))
                .andExpect(jsonPath(docA + ".supplierDocumentNumber").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.documents[?(@.number == '" + b + "')].supplierDocumentNumber").value("F/016441/26"));

        // Kolejny raport z nakładającego się okresu: zmieniona linia 1, nowa linia 3, brak linii 2 (zostaje w bazie).
        upload(owner, report(STORE_NUMBER,
                line("PZ", a, "09516048398", 1, "4", "4.49", "17.96"),
                line("PZ", a, "09516048398", 3, "1", "10", "10"),
                line("PZ", b, "F/016441/26", 1, "12", "1.5", "18")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.updatedCount").value(1));

        String idA = ((String) com.jayway.jsonpath.JsonPath.<List<Object>>read(
                mvc.perform(get(url("/pz")).cookie(owner)).andReturn().getResponse().getContentAsString(),
                docA + ".id").getFirst());
        mvc.perform(get(url("/pz/" + idA)).cookie(owner))
                .andExpect(jsonPath("$.lineCount").value(3))
                .andExpect(jsonPath("$.supplierDocumentNumber").value("09516048398"))
                .andExpect(jsonPath("$.lines[0].lineNumber").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(4))
                .andExpect(jsonPath("$.lines[0].marginPercent").value(31.5))
                .andExpect(jsonPath("$.lines[1].lineNumber").value(2))
                .andExpect(jsonPath("$.lines[2].lineNumber").value(3))
                .andExpect(jsonPath("$.purchaseValue").value(27.96));

        mvc.perform(get(importsUrl()).cookie(owner))
                .andExpect(jsonPath("$[0].updatedCount").value(1))
                .andExpect(jsonPath("$[0].importedBy").isNotEmpty());
    }

    @Test
    void listsDocumentsInPagesOfOneHundred() throws Exception {
        Cookie owner = login();
        String[][] lines = new String[101][];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = line("WZ", "WZ%04d".formatted(i), "0", 1, "1", "1", "1");
        }
        upload(owner, report(STORE_NUMBER, lines)).andExpect(status().isCreated());

        mvc.perform(get(url("/wz")).cookie(owner))
                .andExpect(jsonPath("$.documents.length()").value(100))
                .andExpect(jsonPath("$.documents[0].number").value("WZ0000"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.matchingCount").value(101))
                .andExpect(jsonPath("$.purchaseTotal").value(101));
        mvc.perform(get(url("/wz")).param("page", "1").cookie(owner))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].number").value("WZ0100"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void rejectsReportOfAnotherStoreBrokenLinesAndIncompleteFile() throws Exception {
        Cookie owner = login();

        upload(owner, report("10023", line("PZ", docNumber(), "", 1, "1", "1", "1")))
                .andExpect(status().isConflict());

        upload(owner, report(STORE_NUMBER, line("PZ", docNumber(), "", 1, "1", "1", "1"),
                withItm8(line("PZ", docNumber(), "", 1, "1", "1", "1"), "ABC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));

        String number = docNumber();
        upload(owner, report(STORE_NUMBER, line("PZ", number, "", 1, "1", "1", "1"), line("PZ", number, "", 1, "2", "1", "2")))
                .andExpect(status().isBadRequest());

        List<String[]> rows = reportRows(STORE_NUMBER, 5, List.<String[]>of(line("PZ", docNumber(), "", 1, "1", "1", "1")));
        upload(owner, workbook(rows)).andExpect(status().isBadRequest());

        mvc.perform(multipart(importsUrl())
                        .file(new MockMultipartFile("file", "dokumenty.xlsx", "application/octet-stream", "nie excel".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctionsAttachedToDeliveryCorrectItsValuesAndDeliveryNote() throws Exception {
        Cookie owner = login();
        // Własny sklep: inne testy wgrywają korekty od tego samego dostawcy.
        String number = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String store = createStore(number, "Sklep korekt");
        String delivery = docNumber() + "00";
        String otherSupplierDelivery = docNumber() + "00";
        String storeError = "PK" + docNumber();
        String supplierCorrection = "WZ" + docNumber();
        String itemA = "0000099042772";
        String itemB = "0000099042773";
        String itemC = "0000099042774";

        upload(owner, store, report(number,
                withItm8(amounts(line("PZ", delivery, "F/1/26", 1, "960", "1.99", "1910.4")), itemA),
                withItm8(amounts(line("PZ", delivery, "F/1/26", 2, "1344", "1.99", "2674.56")), itemB),
                withSupplier(line("PZ", otherSupplierDelivery, "F/2/26", 1, "1", "1", "1"), "108", "RADIX-BIS"),
                // Korekta błędu sklepu: przyszło mniej B, za to więcej A i artykuł C, którego nie było w przyjęciu.
                withItm8(amounts(correction(line("PK", storeError, "", 1, "192", "1.99", "382.08"))), itemA),
                withItm8(amounts(correction(line("PK", storeError, "", 2, "-576", "1.99", "-1146.24"))), itemB),
                withItm8(amounts(correction(line("PK", storeError, "", 3, "384", "1.99", "764.16"))), itemC),
                // Korekta od dostawcy: obniżka ceny B, bez zmiany ilości.
                withItm8(amounts(correction(line("WZ", supplierCorrection, "FK/7/26", 1, "0", "1.5", "-100"))), itemB)))
                .andExpect(status().isCreated());

        String deliveryId = id(store, "/pz", delivery);
        String otherId = id(store, "/pz", otherSupplierDelivery);
        String storeErrorId = id(store, "/pk", storeError);
        String supplierCorrectionId = id(store, "/wz", supplierCorrection);

        // Podpowiedzi: tylko korekty tego dostawcy, najpierw ta z największą liczbą wspólnych artykułów.
        mvc.perform(get(url(store, "/pz/" + deliveryId + "/corrections/candidates")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value(storeError))
                .andExpect(jsonPath("$[0].commonItems").value(2))
                .andExpect(jsonPath("$[1].number").value(supplierCorrection));
        mvc.perform(get(url(store, "/wz/" + supplierCorrectionId + "/corrections/candidates")).cookie(owner))
                .andExpect(jsonPath("$[0].number").value(delivery))
                .andExpect(jsonPath("$[?(@.number == '" + otherSupplierDelivery + "')]").isEmpty());

        // Przypięcie z obu stron: korekta błędu sklepu z przyjęcia, korekta od dostawcy z korekty.
        mvc.perform(post(url(store, "/pz/" + deliveryId + "/corrections")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"" + storeErrorId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrections.length()").value(1));
        mvc.perform(post(url(store, "/wz/" + supplierCorrectionId + "/corrections")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"" + deliveryId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionOf.number").value(delivery));

        // Korekta poprawia jedno przyjęcie, a przyjęcie innego dostawcy odpada.
        mvc.perform(post(url(store, "/pz/" + otherId + "/corrections")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"" + storeErrorId + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url(store, "/pk/" + storeErrorId + "/corrections")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"documentId\":\"" + otherId + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(url(store, "/pz/" + deliveryId)).cookie(owner))
                .andExpect(jsonPath("$.purchaseValue").value(4584.96))
                .andExpect(jsonPath("$.correctionTotals.count").value(2))
                .andExpect(jsonPath("$.correctionTotals.purchaseValue").value(4484.96))
                .andExpect(jsonPath("$.corrected.purchaseValue").value(4484.96))
                .andExpect(jsonPath("$.corrected.lines.length()").value(3))
                .andExpect(jsonPath("$.corrected.lines[0].quantity").value(1152))
                .andExpect(jsonPath("$.corrected.lines[0].originalQuantity").value(960))
                .andExpect(jsonPath("$.corrected.lines[1].quantity").value(768))
                .andExpect(jsonPath("$.corrected.lines[1].purchaseValue").value(1428.32))
                .andExpect(jsonPath("$.corrected.lines[1].purchasePrice").value(1.8598))
                .andExpect(jsonPath("$.corrected.lines[1].corrected").value(true))
                .andExpect(jsonPath("$.corrected.lines[2].itm8").value(itemC))
                .andExpect(jsonPath("$.corrected.lines[2].lineNumber").value(3))
                .andExpect(jsonPath("$.corrected.lines[2].originalQuantity").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(url(store, "/pz")).param("query", delivery).cookie(owner))
                .andExpect(jsonPath("$.documents[0].correctionTotals.count").value(2))
                .andExpect(jsonPath("$.documents[0].correctionTotals.purchaseValue").value(4484.96));
        mvc.perform(get(url(store, "/pk")).param("query", storeError).cookie(owner))
                .andExpect(jsonPath("$.documents[0].correctionOf.number").value(delivery));

        byte[] pdf = mvc.perform(get(url(store, "/pz/" + deliveryId + "/delivery-note")).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(get(url(store, "/pk/" + storeErrorId + "/delivery-note")).cookie(owner))
                .andExpect(status().isBadRequest());

        // Filtry listy: przypięte korekty, faktura (sklep bez KSeF i bez przypiętych), artykuł w liniach.
        mvc.perform(get(url(store, "/pz")).param("corrections", "with").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.documents[0].number").value(delivery));
        mvc.perform(get(url(store, "/pz")).param("corrections", "without").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.documents[0].number").value(otherSupplierDelivery));
        mvc.perform(get(url(store, "/pz")).param("invoice", "with").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(0));
        mvc.perform(get(url(store, "/pz")).param("invoice", "without").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(2));
        mvc.perform(get(url(store, "/pz")).param("product", "kanapka sonko").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(2));
        mvc.perform(get(url(store, "/pz")).param("product", itemB).cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.documents[0].number").value(delivery));
        mvc.perform(get(url(store, "/pz")).param("product", "kanapka rogal").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(0));

        // Raport przyjęć za okres: wiersz przyjęcia z korektami (opisanymi numerem faktury korygującej), pod nim zakup
        // po korektach według rejonów.
        byte[] report = mvc.perform(get(url(store, "/pz/period-report")).param("from", "2026-10-21")
                        .param("to", "2026-10-21").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("przyjecia-2026-10-21-2026-10-21.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        // Grupy rejonów otwierają się zwinięte.
        org.assertj.core.api.Assertions.assertThat(sheet(report)).contains(
                ">21.10.2026<", ">" + delivery.substring(0, delivery.length() - 2) + "<", ">F/1/26<", ">F/2/26<",
                "brak nr faktury (Korekta błędu sklepu " + storeError, "; FK/7/26<",
                "<v>4584.96</v>", "<v>4484.96</v>", "<v>-100</v>", "outlineLevel=\"1\" hidden=\"1\"",
                "collapsed=\"1\"", ">01<", ">2 przyjęcia<", "Podgląd wygenerowany")
                .doesNotContain("Korekta od dostawcy " + supplierCorrection, "Pominięci dostawcy");

        // Pomijani dostawcy: ich przyjęć nie ma w raporcie, a lista trafia do stopki i do zamknięcia.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url(store, "/pz/report-excluded-suppliers")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[\"999\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url(store, "/pz/report-excluded-suppliers")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"supplierCodes\":[\"108\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedSuppliers.length()").value(1))
                .andExpect(jsonPath("$.excludedSuppliers[0].code").value("108"))
                .andExpect(jsonPath("$.excludedSuppliers[0].name").value("RADIX-BIS"));
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(get(url(store, "/pz/period-report"))
                        .param("from", "2026-10-21").param("to", "2026-10-21").cookie(owner))
                .andReturn().getResponse().getContentAsByteArray()))
                .contains(">1 przyjęcie<", "Pominięci dostawcy: 108 RADIX-BIS").doesNotContain(">F/2/26<");

        // Zamknięcie okresu: nie może nachodzić na inne ani sięgać w przyszłość.
        String closing = com.jayway.jsonpath.JsonPath.read(mvc.perform(post(url(store, "/pz/report-closings"))
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-10-21\",\"to\":\"2026-10-21\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post(url(store, "/pz/report-closings")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2026-10-01\",\"to\":\"2026-10-21\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(url(store, "/pz/report-closings")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2099-01-01\",\"to\":\"2099-01-02\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url(store, "/pz/report-closings")).cookie(owner))
                .andExpect(jsonPath("$.closings.length()").value(1))
                .andExpect(jsonPath("$.closings[0].cancellable").value(true))
                .andExpect(jsonPath("$.pendingChanges").value(0));
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(
                        get(url(store, "/pz/report-closings/" + closing + "/xlsx")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()))
                .contains("<v>4484.96</v>", "Korekta błędu sklepu " + storeError, "Raport zamknięty",
                        "Pominięci dostawcy: 108 RADIX-BIS")
                .doesNotContain(">F/2/26<");
        mvc.perform(get(url(store, "/pz/period-report")).param("from", "2026-10-21").param("to", "2026-10-20")
                        .cookie(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(get(url(store, "/pz/period-report")).param("from", "2025-01-01").param("to", "2026-10-21")
                        .cookie(owner))
                .andExpect(status().isBadRequest());

        // Odpięcie to znacznik; ponowne przypięcie przywraca korektę.
        mvc.perform(delete(url(store, "/pz/" + deliveryId + "/corrections/" + storeErrorId)).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionTotals.count").value(1))
                .andExpect(jsonPath("$.corrected.lines.length()").value(2));
        mvc.perform(get(url(store, "/pz/" + deliveryId + "/corrections/candidates")).cookie(owner))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value(storeError));

        // Odpięcie korekty po zamknięciu nie zmienia zamkniętego raportu, tylko czeka na następny.
        mvc.perform(get(url(store, "/pz/report-closings")).cookie(owner))
                .andExpect(jsonPath("$.pendingChanges").value(1))
                .andExpect(jsonPath("$.closings[0].pendingChanges").value(1));
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(
                        get(url(store, "/pz/report-closings/" + closing + "/xlsx")).cookie(owner))
                .andReturn().getResponse().getContentAsByteArray()))
                .contains("Korekta błędu sklepu " + storeError).doesNotContain("Zmiany w zamkniętych okresach");
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(
                        get(url(store, "/pz/period-report/changes")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()))
                .contains("Zmiany w zamkniętych okresach", "odpięte: brak nr faktury (Korekta błędu sklepu " + storeError,
                        "Okres 21.10.2026 - 21.10.2026");
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(get(url(store, "/pz/period-report"))
                        .param("from", "2026-10-22").param("to", "2026-10-22").cookie(owner))
                .andReturn().getResponse().getContentAsByteArray()))
                .contains("Brak przyjęć towaru w okresie 22.10.2026", "Zmiany w zamkniętych okresach");

        // Następne zamknięcie przejmuje zmianę; cofnąć można tylko ostatnie, a przywrócić - gdy nic po nim nie zamknięto.
        String next = com.jayway.jsonpath.JsonPath.read(mvc.perform(post(url(store, "/pz/report-closings"))
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-10-22\",\"to\":\"2026-10-22\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get(url(store, "/pz/report-closings")).cookie(owner))
                .andExpect(jsonPath("$.pendingChanges").value(0));
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(
                        get(url(store, "/pz/report-closings/" + next + "/xlsx")).cookie(owner))
                .andReturn().getResponse().getContentAsByteArray()))
                .contains("Zmiany w zamkniętych okresach", "odpięte: brak nr faktury (Korekta błędu sklepu " + storeError);
        mvc.perform(delete(url(store, "/pz/report-closings/" + closing)).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(delete(url(store, "/pz/report-closings/" + next)).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(url(store, "/pz/report-closings")).cookie(owner))
                .andExpect(jsonPath("$.closings.length()").value(1))
                .andExpect(jsonPath("$.pendingChanges").value(1));
        mvc.perform(post(url(store, "/pz/report-closings/" + next + "/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(url(store, "/pz/report-closings")).cookie(owner))
                .andExpect(jsonPath("$.pendingChanges").value(0));

        // Faktura spoza KSeF: dostawca z listy i numer wpisany ręcznie; raport opisuje nią korektę.
        String supplier = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/stores/" + store + "/suppliers")
                        .cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Piekarnia bez KSeF\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String manualInvoice = com.jayway.jsonpath.JsonPath.read(mvc.perform(
                        post(url(store, "/pk/" + storeErrorId + "/manual-invoices")).cookie(owner).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":\"" + supplier
                                        + "\",\"invoiceNumber\":\" KP/5/26 \",\"issueDate\":\"2026-10-23\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualInvoiceAllowed").value(true))
                .andExpect(jsonPath("$.manualInvoices[0].invoiceNumber").value("KP/5/26"))
                .andExpect(jsonPath("$.manualInvoices[0].supplierName").value("Piekarnia bez KSeF"))
                .andReturn().getResponse().getContentAsString(), "$.manualInvoices[0].id");
        org.assertj.core.api.Assertions.assertThat(sheet(mvc.perform(
                        get(url(store, "/pz/report-closings/" + next + "/xlsx")).cookie(owner))
                .andReturn().getResponse().getContentAsByteArray()))
                .contains("odpięte: KP/5/26 z 23.10.2026");
        // Przyjęcie ma jedną fakturę; z ręczną liczy się jako przyjęcie z fakturą.
        String invoiceJson = "{\"supplierId\":\"" + supplier + "\",\"invoiceNumber\":\"F/9/26\"}";
        mvc.perform(post(url(store, "/pz/" + otherId + "/manual-invoices")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(invoiceJson))
                .andExpect(status().isOk());
        mvc.perform(post(url(store, "/pz/" + otherId + "/manual-invoices")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + supplier + "\",\"invoiceNumber\":\"F/10/26\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get(url(store, "/pz")).param("invoice", "with").cookie(owner))
                .andExpect(jsonPath("$.matchingCount").value(1))
                .andExpect(jsonPath("$.documents[0].manualInvoices[0].invoiceNumber").value("F/9/26"));
        // Do innych dokumentów niż przyjęcia i ich korekty faktury się nie wpisuje; usunięcie to znacznik.
        mvc.perform(post(url(store, "/ppp/" + otherId + "/manual-invoices")).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(invoiceJson))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(url(store, "/pk/" + storeErrorId + "/manual-invoices/" + manualInvoice)).cookie(owner)
                        .with(csrf()))
                .andExpect(jsonPath("$.manualInvoices.length()").value(0));
        mvc.perform(post(url(store, "/pk/" + storeErrorId + "/manual-invoices/" + manualInvoice + "/restore"))
                        .cookie(owner).with(csrf()))
                .andExpect(jsonPath("$.manualInvoices.length()").value(1));
    }

    /** Arkusz z pliku .xlsx (XML pierwszego arkusza). */
    private static String sheet(byte[] xlsx) throws java.io.IOException {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** Id dokumentu o numerze z listy typu. */
    private String id(String store, String typePath, String number) throws Exception {
        String json = mvc.perform(get(url(store, typePath)).param("query", number).cookie(login()))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<List<String>>read(json,
                "$.documents[?(@.number == '" + number + "')].id").getFirst();
    }

    /** Korekta z dnia po przyjęciu. */
    private static String[] correction(String[] line) {
        line[4] = "22/10/2026 11:31:54";
        return line;
    }

    /** Sprzedaż i marża zerowe, żeby sumy zależały tylko od zakupu. */
    private static String[] amounts(String[] line) {
        line[19] = "0";
        line[20] = "0";
        line[22] = "0";
        line[23] = "0";
        line[24] = "0%";
        return line;
    }

    private ResultActions upload(Cookie owner, byte[] file) throws Exception {
        return upload(owner, storeId, file);
    }

    private ResultActions upload(Cookie owner, String store, byte[] file) throws Exception {
        return mvc.perform(multipart("/api/stores/" + store + "/imports/documents")
                .file(new MockMultipartFile("file", "Zeszyt2.xlsx", "application/octet-stream", file))
                .cookie(owner).with(csrf()));
    }

    private String url(String path) {
        return url(storeId, path);
    }

    private static String url(String store, String path) {
        return "/api/stores/" + store + "/documents" + path;
    }

    private String importsUrl() {
        return "/api/stores/" + storeId + "/imports/documents";
    }

    @BeforeEach
    void createStore() throws Exception {
        if (storeId != null) {
            return;
        }
        storeId = createStore(STORE_NUMBER, "Sklep dokumentów");
    }

    private String createStore(String number, String name) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"" + name + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private static String docNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
    }

    /** Linia raportu w układzie z prawdziwego pliku (teksty z dopełnieniem spacjami jak w systemie sklepu). */
    private static String[] line(String type, String number, String supplierDocument, int lineNumber, String quantity,
            String price, String value) {
        return new String[] {"010102", "PIECZYWO LEKKIE               ", "538",
                "SCAPR POLSKA SP.Z.O.O.                                      ", "21/10/2026 00:00:00", type, number,
                supplierDocument, String.valueOf(lineNumber), "0000061515426", "5902180810132",
                "LEKKA KANAPKA CZEKOLADA 30G   ", "SONKO     ", "Sztuka", quantity, price, "4.99", "4.99", value,
                "4.99", "4.75", "5", "0.24", "8.6", "31,5%"};
    }

    private static String[] withItm8(String[] line, String itm8) {
        line[9] = itm8;
        return line;
    }

    private static String[] withSupplier(String[] line, String code, String name) {
        line[2] = code;
        line[3] = name;
        return line;
    }

    private static byte[] report(String storeNumber, String[]... lines) throws IOException {
        return workbook(reportRows(storeNumber, lines.length, List.of(lines)));
    }

    private static List<String[]> reportRows(String storeNumber, int declaredLines, List<String[]> lines) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"sam. 24/10/2026 - 16:10:50                    " + declaredLines + " linie"});
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - " + storeNumber});
        rows.add(new String[] {"Statystyka: Wejścia /Wyjścia : Poziom szczegółu :Non détaillée"});
        rows.add(new String[] {"Wybór danych : OD 20/10/2026 do 24/10/2026"});
        rows.add(new String[] {"Kryteria wyboru : Nomenklatura od 00 do 999999"});
        rows.add(new String[] {});
        rows.add(HEADER);
        rows.addAll(lines);
        rows.add(new String[] {"Razem:  ", "", "", "", "", "", "", "", "", "", "", "", "", "", "103420.32"});
        return rows;
    }

    /** Minimalny skoroszyt .xlsx: wszystkie teksty przez tabelę współdzieloną (jak w raporcie), liczby jako liczby. */
    private static byte[] workbook(List<String[]> rows) throws IOException {
        List<String> shared = new ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                String ref = columnName(c) + (r + 1);
                String value = cells[c];
                boolean number = r > 6 && c >= 14 && c != 21 && c != 24 && value.matches("-?\\d+(\\.\\d+)?");
                if (number) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size()).append("</v></c>");
                    shared.add(value);
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        shared.forEach(s -> strings.append("<si><t xml:space=\"preserve\">").append(escape(s)).append("</t></si>"));
        strings.append("</sst>");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Arkusz1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            put(zip, "xl/sharedStrings.xml", strings.toString());
        }
        return bytes.toByteArray();
    }

    private static String columnName(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index))
                : String.valueOf((char) ('A' + index / 26 - 1)) + (char) ('A' + index % 26);
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
