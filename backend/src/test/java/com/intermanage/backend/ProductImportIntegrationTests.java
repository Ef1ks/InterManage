package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
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

import com.intermanage.backend.security.JwtService;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class ProductImportIntegrationTests {

    private static final String[] HEADER = {"EAN", "NAZWA", "CENA_SPRZEDAZY", "STAN", "ROTACJA", "COLI", "MARKA",
            "CENA_ZAKUPU", "ITM8", "PRIO", "NOM", "DOST", "AKTYWNY", "VAT", "JED", "WAGA", "DLV", "RP", "KAUCJA",
            "REFERENCJA", "KASA", "TYP", "RODZ", "JMSP", "CN"};
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm:ss");
    /** Każdy import w teście ma późniejszą datę raportu niż poprzedni, bo starsze raporty są odrzucane. */
    private static final AtomicInteger REPORT_HOUR = new AtomicInteger();
    /** Wspólny sklep wszystkich testów tej klasy (baza jest współdzielona przez cały kontekst testowy). */
    private static final String STORE_NUMBER = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    private static String storeId;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void importAddsNewProductsAndUpdatesChangedOnes() throws Exception {
        Cookie owner = login();
        String a = itm8();
        String b = itm8();
        String c = itm8();

        upload(owner, report(nextReportDate(), product(a, "8.99", "12.5", "SER GOUDA 150G"), product(b, "3.49", "0", "MLEKO 2% 1L")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.importedBy").isNotEmpty());

        // Zmiana ceny A, B bez zmian w nowym pliku nie występuje, C to nowy kod.
        upload(owner, report(nextReportDate(), product(a, "9.49", "11", "SER GOUDA 150G"), product(c, "1.99", "4", "BULKA")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.unchangedCount").value(0));

        Map<String, Object> productA = jdbc.queryForMap("select * from products where itm8 = ?", a);
        assertThat(productA.get("sale_price")).isEqualTo(new BigDecimal("9.49"));
        assertThat(productA.get("stock")).isEqualTo(new BigDecimal("11.000"));
        assertThat(productA.get("ean")).isEqualTo("59" + a.substring(2));
        assertThat(productA.get("active")).isEqualTo(true);
        assertThat(productA.get("first_seen_import_id")).isNotEqualTo(productA.get("last_seen_import_id"));

        // Produkt, którego zabrakło w pliku, zostaje w bazie z danymi z poprzedniego importu.
        Map<String, Object> productB = jdbc.queryForMap("select * from products where itm8 = ?", b);
        assertThat(productB.get("sale_price")).isEqualTo(new BigDecimal("3.49"));
        assertThat(productB.get("last_seen_import_id")).isEqualTo(productA.get("first_seen_import_id"));

        // Ten sam plik drugi raz niczego nie zmienia.
        upload(owner, report(nextReportDate(), product(a, "9.49", "11", "SER GOUDA 150G"), product(c, "1.99", "4", "BULKA")))
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.unchangedCount").value(2));

        mvc.perform(get(importsUrl()).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").isNumber())
                .andExpect(jsonPath("$.imports[0].unchangedCount").value(2));
    }

    @Test
    void eanReassignmentsKeepEveryItm8EanPairAndAreReported() throws Exception {
        Cookie owner = login();
        String a = itm8();
        String b = itm8();
        String eanFirst = ean();
        String eanSecond = ean();

        upload(owner, report(nextReportDate(), withEan(product(a, "5.00", "1", "JOGURT"), eanFirst)))
                .andExpect(jsonPath("$.eanChangedCount").value(0))
                .andExpect(jsonPath("$.eanMovedCount").value(0));

        // A dostaje nowy EAN, a jego dotychczasowy EAN przechodzi do nowego ITM8 B.
        String result = upload(owner, report(nextReportDate(),
                withEan(product(a, "5.00", "1", "JOGURT"), eanSecond),
                withEan(product(b, "5.00", "1", "JOGURT NOWY"), eanFirst)))
                .andExpect(jsonPath("$.eanChangedCount").value(1))
                .andExpect(jsonPath("$.eanMovedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String importId = ((String) com.jayway.jsonpath.JsonPath.read(result, "$.id"));

        assertThat(jdbc.queryForList("""
                select pe.ean from product_eans pe join products p on p.id = pe.product_id
                where p.itm8 = ? order by pe.id""", String.class, a)).containsExactly(eanFirst, eanSecond);
        assertThat(jdbc.queryForList("""
                select p.itm8 from product_eans pe join products p on p.id = pe.product_id
                where pe.ean = ? order by pe.id""", String.class, eanFirst)).containsExactly(a, b);

        mvc.perform(get(importsUrl() + "/" + importId + "/ean-changes").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.itm8 == '" + a + "')].previousEan").value(eanFirst))
                .andExpect(jsonPath("$[?(@.itm8 == '" + b + "')].previousItm8").value(a));

        // Te same pary w kolejnym pliku nie są zgłaszane drugi raz.
        upload(owner, report(nextReportDate(),
                withEan(product(a, "5.00", "1", "JOGURT"), eanSecond),
                withEan(product(b, "5.00", "1", "JOGURT NOWY"), eanFirst)))
                .andExpect(jsonPath("$.eanChangedCount").value(0))
                .andExpect(jsonPath("$.eanMovedCount").value(0));

        // Korekta pomyłki: A wraca do pierwszego EAN-u, który nadal ma też B - to zmiana EAN, nie nowe przeniesienie.
        upload(owner, report(nextReportDate(),
                withEan(product(a, "5.00", "1", "JOGURT"), eanFirst),
                withEan(product(b, "5.00", "1", "JOGURT NOWY"), eanFirst)))
                .andExpect(jsonPath("$.eanChangedCount").value(1))
                .andExpect(jsonPath("$.eanMovedCount").value(0));
    }

    @Test
    void fileWithInvalidRowsIsRejectedAsAWhole() throws Exception {
        Cookie owner = login();
        String good = itm8();
        String[] broken = product(itm8(), "abc", "1", "ZLA CENA");

        upload(owner, report(nextReportDate(), product(good, "1.00", "1", "OK"), broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1))
                .andExpect(jsonPath("$.rowErrors[0]").value(org.hamcrest.Matchers.containsString("CENA_SPRZEDAZY")));

        assertThat(jdbc.queryForObject("select count(*) from products where itm8 = ?", Integer.class, good)).isZero();
    }

    @Test
    void incompleteFileIsRejected() throws Exception {
        Cookie owner = login();
        byte[] file = workbook(List.of(
                row("Data : " + REPORT_DATE.format(nextReportDate())),
                row(HEADER),
                product(itm8(), "1.00", "1", "JEDEN"),
                row("Ilość linii: :000002")));

        upload(owner, file)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("niekompletny")));
    }

    @Test
    void olderReportThanTheLastImportedIsRejected() throws Exception {
        Cookie owner = login();
        upload(owner, report(nextReportDate(), product(itm8(), "1.00", "1", "NOWY"))).andExpect(status().isCreated());

        upload(owner, report(LocalDateTime.of(2020, 1, 1, 8, 0), product(itm8(), "1.00", "1", "STARY")))
                .andExpect(status().isConflict());
    }

    @Test
    void reportFromAnotherStoreIsRejected() throws Exception {
        Cookie owner = login();
        String item = itm8();

        upload(owner, report("1", nextReportDate(), product(item, "1.00", "1", "OBCY SKLEP")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("sklepu 1,")));
        assertThat(jdbc.queryForObject("select count(*) from products where itm8 = ?", Integer.class, item)).isZero();
    }

    @Test
    void unknownStoreIsForbidden() throws Exception {
        mvc.perform(get("/api/stores/" + java.util.UUID.randomUUID() + "/imports/products").cookie(login())).andExpect(status().isForbidden());
    }

    @Test
    void notAnExcelFileIsRejected() throws Exception {
        Cookie owner = login();
        mvc.perform(multipart(importsUrl())
                        .file(new MockMultipartFile("file", "baza.xlsx", "application/octet-stream", "nie excel".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(importsUrl())
                        .file(new MockMultipartFile("file", "baza.csv", "text/csv", "EAN;NAZWA".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importRequiresLogin() throws Exception {
        mvc.perform(multipart("/api/stores/1/imports/products")
                        .file(new MockMultipartFile("file", "baza.xlsx", "application/octet-stream", new byte[] {1}))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions upload(Cookie owner, byte[] file) throws Exception {
        return mvc.perform(multipart(importsUrl())
                .file(new MockMultipartFile("file", "BAZA DO STRONY.xlsx", "application/octet-stream", file))
                .cookie(owner).with(csrf()));
    }

    private String importsUrl() {
        return "/api/stores/" + storeId + "/imports/products";
    }

    @BeforeEach
    void createStore() throws Exception {
        if (storeId != null) {
            return;
        }
        String json = mvc.perform(post("/api/settings/stores").cookie(login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + STORE_NUMBER + "\",\"name\":\"Sklep testowy\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        storeId = ((String) com.jayway.jsonpath.JsonPath.read(json, "$.id"));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private static LocalDateTime nextReportDate() {
        return LocalDateTime.of(2030, 1, 1, 0, 0).plusHours(REPORT_HOUR.incrementAndGet());
    }

    private static String itm8() {
        return "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
    }

    /** Wiersz produktu w układzie z prawdziwego raportu; EAN wynika z ITM8, żeby był unikalny. */
    private static String[] product(String itm8, String salePrice, String stock, String name) {
        return new String[] {"59" + itm8.substring(2), name, salePrice, stock, "2.125", "9", "MARKA", "5.99", itm8,
                "Tak", "01 01 01", "SCAPR POLSKA SP.Z.O.O.", "Tak", "6", "kg", "0.15000000000000002", "144", "3", "0",
                itm8.replaceFirst("^0+", ""), "2", "1", "Inne", "1", "19051000 ------ N"};
    }

    private static String ean() {
        return "%013d".formatted(ThreadLocalRandom.current().nextLong(1, 9_999_999_999_999L));
    }

    private static String[] withEan(String[] product, String ean) {
        product[0] = ean;
        return product;
    }

    private static String[] row(String... cells) {
        return cells;
    }

    private static byte[] report(LocalDateTime generatedAt, String[]... products) throws IOException {
        return report(STORE_NUMBER, generatedAt, products);
    }

    private static byte[] report(String storeNumber, LocalDateTime generatedAt, String[]... products)
            throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(row("TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber));
        rows.add(row("Data : " + REPORT_DATE.format(generatedAt)));
        rows.add(row("DANE DO IMPEKA"));
        rows.add(row());
        rows.add(row(HEADER));
        rows.addAll(List.of(products));
        rows.add(row());
        rows.add(row("Ilość linii: :%06d".formatted(products.length)));
        return workbook(rows);
    }

    /**
     * Minimalny skoroszyt .xlsx. Teksty nagłówka idą przez tabelę współdzieloną (jak w plikach z Excela),
     * pozostałe jako tekst w komórce, a wartości liczbowe jako liczby.
     */
    private static byte[] workbook(List<String[]> rows) throws IOException {
        List<String> shared = new ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                String value = cells[c];
                if (cells == HEADER) {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size()).append("</v></c>");
                    shared.add(value);
                } else if (value.matches("-?\\d+\\.\\d+|-?[1-9]\\d{0,5}|0")) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(escape(value))
                            .append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder strings = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        shared.forEach(s -> strings.append("<si><t>").append(escape(s)).append("</t></si>"));
        strings.append("</sst>");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Mercalys\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
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

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
