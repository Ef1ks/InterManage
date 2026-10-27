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
import java.nio.charset.StandardCharsets;
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
class SupplierGratisIntegrationTests {

    /** Kolumny raportu "Lista dostawców" (pierwsze z kilkudziesięciu, reszty nie czytamy). */
    private static final String[] SUPPLIER_HEADER = {"Kod dost.", "Słowo klucz", "Nazwa firmy 1", "Nr Siret",
            "Nr VAT wewnątrzwspólnotowego", "Nazwa firmy 2", "Kod NIP", "Adres 1", "Adres 2", "Kod pocztowy",
            "Adres Dostawcy", "Telefon", "Faks", "Pochodz."};
    private static final String[] DOCUMENT_HEADER = {"Code nomenclature", "Libellé nomenclature", "Code fournisseur",
            "Libellé fournisseur", "Date Document", "Type Document", "Numéro document interne",
            "Numéro document fournisseur", "N° ligne", "ITM8", "EAN13", "Libellé article", "Marque", "Unité Mesure",
            "Quantité", "Prix achat FIFO", "Prix Vente actuel", "Prix vente document", "Valeur FIFO",
            "Valeur Vente TTC", "Valeur Vente HT", "TVA", "Valeur TVA", "Marge en valeur", "Marge en %"};

    @Autowired
    MockMvc mvc;

    @Test
    void importsSupplierListAndMergesLaterReports() throws Exception {
        Cookie owner = login();
        String storeNumber = storeNumber();
        String storeId = createStore(owner, storeNumber);

        uploadSuppliers(owner, storeId, supplierReport(storeNumber, 2,
                supplier("214", "MAZURSKIE", "MAZURSKIE MIODY BOGDAN", "PIASECKI SP. Z O. O.", "Bezpośr."),
                supplier("538", "SCAPR", "SCAPR POLSKA SP.Z.O.O.", "", "Baza")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-10-25T12:46:21"));

        // Kolejna lista: zmieniona nazwa, nowy dostawca, brak SCAPR (zostaje w bazie).
        uploadSuppliers(owner, storeId, supplierReport(storeNumber, 2,
                supplier("214", "MAZURSKIE", "MAZURSKIE MIODY", "", "Bezpośr."),
                supplier("19", "TRYTOMA", "TRYTOMA SP. Z O.O.", "", "Bezpośr.")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1));

        mvc.perform(get(suppliersUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.code == '214')].name").value("MAZURSKIE MIODY"))
                .andExpect(jsonPath("$[?(@.code == '538')].name").value("SCAPR POLSKA SP.Z.O.O."))
                .andExpect(jsonPath("$[0].manual").value(false));
        mvc.perform(get(supplierImportsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.supplierCount").value(3))
                .andExpect(jsonPath("$.imports.length()").value(2));

        // Raport innego sklepu, niekompletny i z powtórzonym kodem.
        uploadSuppliers(owner, storeId, supplierReport("10023", 1, supplier("1", "X", "X", "", "Baza")))
                .andExpect(status().isConflict());
        uploadSuppliers(owner, storeId, supplierReport(storeNumber, 5, supplier("1", "X", "X", "", "Baza")))
                .andExpect(status().isBadRequest());
        uploadSuppliers(owner, storeId, supplierReport(storeNumber, 2,
                supplier("1", "X", "X", "", "Baza"), supplier("1", "Y", "Y", "", "Baza")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));
    }

    @Test
    void registersTransferAsGratisFromChosenSupplier() throws Exception {
        Cookie owner = login();
        String storeNumber = storeNumber();
        String storeId = createStore(owner, storeNumber);
        uploadSuppliers(owner, storeId, supplierReport(storeNumber, 1,
                supplier("214", "MAZURSKIE", "MAZURSKIE MIODY", "", "Bezpośr.")))
                .andExpect(status().isCreated());
        String imported = JsonPath.read(mvc.perform(get(suppliersUrl(storeId)).cookie(owner))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        // Dostawca spoza systemu sklepu: jest tylko w aplikacji, drugi o tej samej nazwie to pomyłka.
        String manual = JsonPath.read(mvc.perform(post(suppliersUrl(storeId)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Przedstawiciel   Lubuskie \",\"nip\":\"929-124-30-24\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Przedstawiciel Lubuskie"))
                .andExpect(jsonPath("$.nip").value("9291243024"))
                .andExpect(jsonPath("$.code").value(nullValue()))
                .andExpect(jsonPath("$.manual").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post(suppliersUrl(storeId)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"przedstawiciel lubuskie\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(suppliersUrl(storeId)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());

        String transfer = "PPP" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        String plain = "PPP" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        String receipt = "PZ" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        uploadDocuments(owner, storeId, documentReport(storeNumber,
                line("PPP", transfer, 2, "19", "0000099007782", "GRATIS SPOŻYWKA MIX           ", "0", "-0.01"),
                line("PPP", transfer, 4, "214", "0000099037716", "MIODOWA MALINA 400G", "6", "65.94"),
                line("PPP", plain, 1, "214", "0000099037717", "MIOD Z PASTA TRUSKAWKA 400G", "6", "65.94"),
                line("PZ", receipt, 1, "214", "0000099037718", "MIOD WIEL JAB GRU SL 400G", "6", "65.94")))
                .andExpect(status().isCreated());

        String list = mvc.perform(get(documentsUrl(storeId, "/ppp")).cookie(owner))
                .andExpect(jsonPath("$.documents[?(@.number == '" + transfer + "')].gratisCandidate").value(true))
                .andExpect(jsonPath("$.documents[?(@.number == '" + plain + "')].gratisCandidate").value(false))
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.<List<String>>read(list, "$.documents[?(@.number == '" + transfer + "')].id").getFirst();
        String plainId = JsonPath.<List<String>>read(list, "$.documents[?(@.number == '" + plain + "')].id").getFirst();
        String receiptId = JsonPath.read(mvc.perform(get(documentsUrl(storeId, "/pz")).cookie(owner))
                .andReturn().getResponse().getContentAsString(), "$.documents[0].id");

        mvc.perform(get(documentsUrl(storeId, "/ppp/" + transferId)).cookie(owner))
                .andExpect(jsonPath("$.gratisAllowed").value(true))
                .andExpect(jsonPath("$.gratisCandidate").value(true))
                .andExpect(jsonPath("$.gratis").value(nullValue()));

        // Bez pozycji GRATIS też można zarejestrować; przyjęcia towaru - nie.
        registerGratis(owner, storeId, "/ppp/" + plainId, imported).andExpect(status().isOk())
                .andExpect(jsonPath("$.gratis.supplierCode").value("214"));
        registerGratis(owner, storeId, "/pz/" + receiptId, imported).andExpect(status().isBadRequest());

        registerGratis(owner, storeId, "/ppp/" + transferId, imported)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gratis.supplierName").value("MAZURSKIE MIODY"))
                .andExpect(jsonPath("$.gratis.registeredBy").isNotEmpty())
                .andExpect(jsonPath("$.saleValueGross").value(65.93));
        mvc.perform(get(documentsUrl(storeId, "/ppp")).cookie(owner))
                .andExpect(jsonPath("$.documents[?(@.number == '" + transfer + "')].gratis.supplierCode").value("214"));

        // Zmiana dostawcy, cofnięcie i przywrócenie - wraca ostatni dostawca.
        registerGratis(owner, storeId, "/ppp/" + transferId, manual)
                .andExpect(jsonPath("$.gratis.supplierName").value("Przedstawiciel Lubuskie"));
        mvc.perform(delete(documentsUrl(storeId, "/ppp/" + transferId + "/gratis")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gratis").value(nullValue()));
        mvc.perform(delete(documentsUrl(storeId, "/ppp/" + transferId + "/gratis")).cookie(owner).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(documentsUrl(storeId, "/ppp/" + transferId + "/gratis/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gratis.supplierName").value("Przedstawiciel Lubuskie"));
        mvc.perform(post(documentsUrl(storeId, "/ppp/" + transferId + "/gratis/restore")).cookie(owner).with(csrf()))
                .andExpect(status().isConflict());

        // Dostawca innego sklepu nie pasuje.
        String otherStoreNumber = storeNumber();
        String otherStore = createStore(owner, otherStoreNumber);
        String foreign = JsonPath.read(mvc.perform(post(suppliersUrl(otherStore)).cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Obcy\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        registerGratis(owner, storeId, "/ppp/" + transferId, foreign).andExpect(status().isBadRequest());
    }

    private ResultActions registerGratis(Cookie owner, String storeId, String document, String supplierId)
            throws Exception {
        return mvc.perform(put(documentsUrl(storeId, document + "/gratis")).cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":\"" + supplierId + "\"}"));
    }

    private ResultActions uploadSuppliers(Cookie owner, String storeId, byte[] file) throws Exception {
        return mvc.perform(multipart(supplierImportsUrl(storeId))
                .file(new MockMultipartFile("file", "nananna.xlsx", "application/octet-stream", file))
                .cookie(owner).with(csrf()));
    }

    private ResultActions uploadDocuments(Cookie owner, String storeId, byte[] file) throws Exception {
        return mvc.perform(multipart("/api/stores/" + storeId + "/imports/documents")
                .file(new MockMultipartFile("file", "Zeszyt2.xlsx", "application/octet-stream", file))
                .cookie(owner).with(csrf()));
    }

    private static String suppliersUrl(String storeId) {
        return "/api/stores/" + storeId + "/suppliers";
    }

    private static String supplierImportsUrl(String storeId) {
        return "/api/stores/" + storeId + "/imports/suppliers";
    }

    private static String documentsUrl(String storeId, String path) {
        return "/api/stores/" + storeId + "/documents" + path;
    }

    private static String storeNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep gratisów\",\"active\":true}"))
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

    private static String[] supplier(String code, String keyword, String name, String name2, String origin) {
        return new String[] {code, keyword, name, "", "", name2, "8681704053", "UL. PRZEMYSŁOWA 40", "", "32765",
                "RZEZAWA", "11 - 11", "11 - ", origin};
    }

    /** Lista dostawców w układzie z prawdziwego pliku: nagłówek sklepu, data, tytuł, kolumny, wiersze i stopka. */
    private static byte[] supplierReport(String storeNumber, int declaredRows, String[]... suppliers) throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber});
        rows.add(new String[] {"Data : 25/10/2026  12:46:21"});
        rows.add(new String[] {"LISTA DOSTAWCÓW"});
        rows.add(new String[] {});
        rows.add(SUPPLIER_HEADER);
        rows.addAll(List.of(suppliers));
        rows.add(new String[] {});
        rows.add(new String[] {});
        rows.add(new String[] {"Ilość linii:  " + declaredRows});
        return workbook(rows);
    }

    private static String[] line(String type, String number, int lineNumber, String supplierCode, String itm8,
            String name, String quantity, String saleValue) {
        return new String[] {"010102", "MIODY", supplierCode, "DOSTAWCA " + supplierCode, "21/10/2026 00:00:00", type,
                number, "0", String.valueOf(lineNumber), itm8, "", name, "", "Sztuka", quantity, "0", "10.99", "10.99",
                "0", saleValue, saleValue, "5", "0", "0", "0%"};
    }

    private static byte[] documentReport(String storeNumber, String[]... lines) throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"sam. 24/10/2026 - 16:10:50                    " + lines.length + " linie"});
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - " + storeNumber});
        rows.add(new String[] {"Wybór danych : OD 20/10/2026 do 24/10/2026"});
        rows.add(new String[] {});
        rows.add(DOCUMENT_HEADER);
        rows.addAll(List.of(lines));
        rows.add(new String[] {"Razem:  "});
        return workbook(rows);
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
                sheet.append("<c r=\"").append(columnName(c)).append(r + 1).append("\" t=\"s\"><v>")
                        .append(shared.size()).append("</v></c>");
                shared.add(cells[c]);
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

    private static String columnName(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index))
                : String.valueOf((char) ('A' + index / 26 - 1)) + (char) ('A' + index % 26);
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
