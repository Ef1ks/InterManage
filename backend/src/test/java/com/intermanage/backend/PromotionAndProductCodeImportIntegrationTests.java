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
import java.sql.Date;
import java.time.LocalDate;
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
import com.intermanage.backend.common.component.PdfFonts;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class PromotionAndProductCodeImportIntegrationTests {

    /**
     * Nagłówki z pozycjami jak w Taryfy.pdf: dwuwierszowe ("Cena" nad "Zak.Katalog.") to dwa napisy, nad i pod
     * wysokością pozostałych.
     */
    private static final String[] HEADER = {"Data końca", "Kod Artykułu", "EAN Prio", "Nazwa Artykułu",
            "Data początku", "Cena", "Zak.Katalog.", "Ceny Sprzed.", "Przegląd", "Cena Promo", "Marża"};
    private static final float[] HEADER_X = {30.4f, 83.4f, 145.4f, 217.9f, 303.3f, 373f, 361.6f, 416f, 423.2f, 473f,
            538.1f};
    private static final float[] HEADER_Y = {759.5f, 759.5f, 759.5f, 759.5f, 759.5f, 763f, 756f, 763f, 756f, 759.5f,
            759.5f};
    /** Wartości: teksty od lewej, ceny wyrównane do prawej, więc zaczynają się za nagłówkiem. */
    private static final float[] VALUE_X = {35.6f, 82f, 137.5f, 188.5f, 313.1f, 394.9f, 450.4f, 505.9f, 558.1f};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PdfFonts fonts;

    @Test
    void importsPromotionsFromPdfAndMergesByArticleAndPeriod() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String storeId = createStore(owner, storeNumber);

        uploadPromotions(owner, storeId, promotions(storeNumber, "02/11/2026 - 06:39:56", null,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO FUNKCJONALNE PLAY 330ML", "29/10/26",
                        "4,17", "9,99", "5,99", "11,68"),
                // Bez ceny zakupu: puste pole w swojej kolumnie, reszta cen na miejscu.
                promotion("31/12/26", "0000006903715", "5901067411240", "NAPOJ MANGO-POMARANCZA 0.2L", "25/09/26",
                        null, "2,99", "1,49", "-31,61"),
                promotion("04/11/26", "0000005803814", "0000075041670", "PIWO CORONA EXTRA 330ML", "29/10/26",
                        "3,89", "5,99", "5,99", "16,36")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.createdCount").value(3))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-11-02T06:39:56"))
                .andExpect(jsonPath("$.endFrom").value("2026-11-02"))
                .andExpect(jsonPath("$.endTo").value("2028-11-02"));

        assertThat(jdbc.queryForMap("""
                select ean, name, start_date, end_date, catalog_purchase_price, regular_sale_price, promo_price,
                       margin_percent
                from promotions where store_id = ?::uuid and itm8 = '0000006903715'""", storeId))
                .containsEntry("ean", "5901067411240")
                .containsEntry("name", "NAPOJ MANGO-POMARANCZA 0.2L")
                .containsEntry("start_date", Date.valueOf(LocalDate.of(2026, 9, 25)))
                .containsEntry("end_date", Date.valueOf(LocalDate.of(2026, 12, 31)))
                .containsEntry("catalog_purchase_price", null)
                .containsEntry("regular_sale_price", new BigDecimal("2.99"))
                .containsEntry("promo_price", new BigDecimal("1.49"))
                .containsEntry("margin_percent", new BigDecimal("-31.61"));

        // Nowszy raport: zmieniona cena, nowa promocja tego samego artykułu, odwołana promocja zostaje w bazie.
        uploadPromotions(owner, storeId, promotions(storeNumber, "03/11/2026 - 06:00:00", null,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO FUNKCJONALNE PLAY 330ML", "29/10/26",
                        "4,17", "9,99", "4,99", "1,00"),
                promotion("31/12/26", "0000006903715", "5901067411240", "NAPOJ MANGO-POMARANCZA 0.2L", "25/09/26",
                        null, "2,99", "1,49", "-31,61"),
                promotion("20/11/26", "0000006903715", "5901067411240", "NAPOJ MANGO-POMARANCZA 0.2L", "05/11/26",
                        "1,89", "2,99", "1,99", "-2,00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1));
        assertThat(jdbc.queryForObject("select count(*) from promotions where store_id = ?::uuid", Long.class,
                storeId)).isEqualTo(4);

        mvc.perform(get(promotionsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.imports.length()").value(2));

        // Starszy raport, raport innego sklepu, brakująca strona, ta sama promocja dwa razy i plik, który nie jest PDF.
        uploadPromotions(owner, storeId, promotions(storeNumber, "01/11/2026 - 06:00:00", null,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO", "29/10/26", "4,17", "9,99", "5,99",
                        "11,68")))
                .andExpect(status().isConflict());
        uploadPromotions(owner, storeId, promotions("10023", "04/11/2026 - 06:00:00", null,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO", "29/10/26", "4,17", "9,99", "5,99",
                        "11,68")))
                .andExpect(status().isConflict());
        uploadPromotions(owner, storeId, promotions(storeNumber, "04/11/2026 - 06:00:00", 5,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO", "29/10/26", "4,17", "9,99", "5,99",
                        "11,68")))
                .andExpect(status().isBadRequest());
        uploadPromotions(owner, storeId, promotions(storeNumber, "04/11/2026 - 06:00:00", null,
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO", "29/10/26", "4,17", "9,99", "5,99",
                        "11,68"),
                promotion("07/01/27", "0000099035805", "5419980316691", "PIWO", "29/10/26", "4,17", "9,99", "5,99",
                        "11,68")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));
        mvc.perform(multipart(promotionsUrl(storeId))
                        .file(new MockMultipartFile("file", "Taryfy.pdf", "application/pdf", "nie pdf".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importsProductCodesAndKeepsPairsMissingFromNewerFile() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String storeId = createStore(owner, storeNumber);

        uploadCodes(owner, storeId, productCodes(storeNumber, "02/11/2026 - 06:38", 4,
                code("453", "1304894000009", "SERNIK BLYSKAWICZ.ORYGIN. ", "", "Szt", "1", "0.707", "4.59", "", "0"),
                code("0000000000453", "5900983014894", "SERNIK BLYSKAWICZ.ORYGIN.", " X ", "Szt", "1", "1",
                        "6.4900000000000002", "", "0"),
                code("0000001786962", "2999089000000", "STEK Z ROSTBEFU OK250G", "", "kg", "1", "1", "69.99",
                        "14/10/2028", "0"),
                code("0000000169665", "5900490000427", "PIWO LECH 0.5L BT ZW", " X ", "LIBELLE_UNITEVENTE_NONE", "1",
                        "1", "4.79", "", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.createdCount").value(4))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-11-02T06:38:00"));

        assertThat(jdbc.queryForMap("""
                select name, prio, sale_unit, price_factor, sale_price from product_codes
                where store_id = ?::uuid and itm8 = '0000000000453' and ean = '1304894000009'""", storeId))
                .containsEntry("name", "SERNIK BLYSKAWICZ.ORYGIN.")
                .containsEntry("prio", false)
                .containsEntry("sale_unit", "Szt")
                .containsEntry("price_factor", new BigDecimal("0.707"))
                .containsEntry("sale_price", new BigDecimal("4.59"));
        assertThat(jdbc.queryForMap("""
                select prio, sale_unit, sale_price, deposit_code from product_codes
                where store_id = ?::uuid and ean = '5900490000427'""", storeId))
                .containsEntry("prio", true)
                .containsEntry("sale_unit", null)
                .containsEntry("sale_price", new BigDecimal("4.79"))
                .containsEntry("deposit_code", 1);
        assertThat(jdbc.queryForObject("select removal_date from product_codes where store_id = ?::uuid and ean = ?",
                Date.class, storeId, "2999089000000")).isEqualTo(Date.valueOf(LocalDate.of(2028, 10, 14)));

        // Nowsze multikody: zmieniona cena, nowy EAN, brakująca para zostaje jako historia.
        uploadCodes(owner, storeId, productCodes(storeNumber, "03/11/2026 - 06:00", 4,
                code("0000000000453", "1304894000009", "SERNIK BLYSKAWICZ.ORYGIN.", "", "Szt", "1", "0.707", "4.29",
                        "", "0"),
                code("0000000000453", "5900983014894", "SERNIK BLYSKAWICZ.ORYGIN.", " X ", "Szt", "1", "1", "6.49",
                        "", "0"),
                code("0000000169665", "5900490000427", "PIWO LECH 0.5L BT ZW", " X ", "LIBELLE_UNITEVENTE_NONE", "1",
                        "1", "4.79", "", "1"),
                code("0000000169665", "5900490000434", "PIWO LECH 0.5L BT ZW", "", "Szt", "1", "1", "4.79", "", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1));

        mvc.perform(get(codesUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.codeCount").value(4))
                .andExpect(jsonPath("$.articleCount").value(2))
                .andExpect(jsonPath("$.imports.length()").value(2));

        // Starszy raport, inna liczba linii w stopce, powtórzona para.
        uploadCodes(owner, storeId, productCodes(storeNumber, "01/11/2026 - 06:00", 1,
                code("453", "1304894000009", "SERNIK", "", "Szt", "1", "1", "4.59", "", "0")))
                .andExpect(status().isConflict());
        uploadCodes(owner, storeId, productCodes(storeNumber, "04/11/2026 - 06:00", 2,
                code("453", "1304894000009", "SERNIK", "", "Szt", "1", "1", "4.59", "", "0")))
                .andExpect(status().isBadRequest());
        uploadCodes(owner, storeId, productCodes(storeNumber, "04/11/2026 - 06:00", 2,
                code("453", "1304894000009", "SERNIK", "", "Szt", "1", "1", "4.59", "", "0"),
                code("453", "1304894000009", "SERNIK", "", "Szt", "1", "1", "4.59", "", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));
    }

    @Test
    void findsArticlesByEveryCurrentEanFromProductCodes() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String storeId = createStore(owner, storeNumber);
        String importId = jdbc.queryForObject("""
                insert into product_imports (store_id, file_name, total_rows, created_count, updated_count,
                    unchanged_count, missing_count)
                values (?::uuid, 'test.xlsx', 1, 1, 0, 0, 0) returning id""", String.class, storeId);
        jdbc.update("""
                insert into products (store_id, itm8, ean, name, unit, sale_price, active,
                    first_seen_import_id, last_seen_import_id)
                values (?::uuid, '0000000000453', '5900983014894', 'SERNIK BLYSKAWICZ', 'Szt', 6.49, true,
                    ?::uuid, ?::uuid)""", storeId, importId, importId);

        uploadCodes(owner, storeId, productCodes(storeNumber, "02/11/2026 - 06:38", 2,
                code("453", "5900983014894", "SERNIK", " X ", "Szt", "1", "1", "6.49", "", "0"),
                code("453", "1304894000009", "SERNIK", "", "Szt", "1", "0.707", "4.59", "", "0")))
                .andExpect(status().isCreated());

        // Wprowadzanie danych (skan, pozycje faktury): EAN dodatkowy daje artykuł z jego głównym EAN-em.
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/products").param("ean", "1304894000009")
                        .cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itm8").value("0000000000453"))
                .andExpect(jsonPath("$[0].catalogEan").value("5900983014894"))
                .andExpect(jsonPath("$[0].byItm8").value(false));
        // Kolumna EAN przyjmuje też ITM8 artykułu, z zerami wiodącymi i bez.
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/products").param("ean", "453")
                        .param("ean", "0000000000453").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itm8").value("0000000000453"))
                .andExpect(jsonPath("$[0].catalogEan").value("5900983014894"))
                .andExpect(jsonPath("$[0].byItm8").value(true));
        // Lista towarów i wyszukiwarka produktów: początek EAN-u dodatkowego.
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/catalog").param("ean", "1304894*").cookie(owner))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/stores/" + storeId + "/deli-order/products").param("query", "1304894").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Kod, którego zabrakło w nowszych multikodach, przestaje prowadzić do artykułu.
        uploadCodes(owner, storeId, productCodes(storeNumber, "03/11/2026 - 06:38", 1,
                code("453", "5900983014894", "SERNIK", " X ", "Szt", "1", "1", "6.49", "", "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.missingCount").value(1));
        mvc.perform(get("/api/stores/" + storeId + "/data-entry/products").param("ean", "1304894000009")
                        .cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/stores/" + storeId + "/deli-order/products").param("query", "1304894").cookie(owner))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String[] promotion(String end, String itm8, String ean, String name, String start,
            String purchase, String sale, String promo, String margin) {
        return new String[] {end, itm8, ean, name, start, purchase, sale, promo, margin};
    }

    /**
     * PDF w układzie raportu z systemu sklepu: A4 pionowo, każda komórka to osobny napis, dwie promocje na stronę,
     * w nagłówku strony "Strona: N / M" ({@code declaredPages} pozwala podać złą liczbę stron).
     */
    private byte[] promotions(String storeNumber, String generated, Integer declaredPages, String[]... rows)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(document, bytes);
        document.open();
        BaseFont font = fonts.regular();
        List<List<String[]>> pages = new ArrayList<>();
        for (int i = 0; i < rows.length; i += 2) {
            pages.add(List.of(rows).subList(i, Math.min(i + 2, rows.length)));
        }
        int pageTotal = declaredPages != null ? declaredPages : pages.size();
        for (int page = 0; page < pages.size(); page++) {
            if (page > 0) {
                document.newPage();
            }
            PdfContentByte canvas = writer.getDirectContent();
            text(canvas, font, 20, 817, "lun. " + generated + " Strona: " + (page + 1) + " / " + pageTotal);
            text(canvas, font, 20, 802, "TRYTOMA  Sp. z o.o. : " + storeNumber);
            text(canvas, font, 20, 791, "LISTA BIEŻĄCYCH I PRZYSZŁYCH PROMOCJI");
            text(canvas, font, 20, 780, "(DATY KOŃCA 02/11/26 - 02/11/28)");
            for (int column = 0; column < HEADER.length; column++) {
                text(canvas, font, HEADER_X[column], HEADER_Y[column], HEADER[column]);
            }
            float y = 746;
            for (String[] row : pages.get(page)) {
                for (int column = 0; column < row.length; column++) {
                    if (row[column] != null) {
                        text(canvas, font, VALUE_X[column], y, row[column]);
                    }
                }
                y -= 10;
            }
        }
        document.close();
        return bytes.toByteArray();
    }

    private static void text(PdfContentByte canvas, BaseFont font, float x, float y, String value) {
        canvas.beginText();
        canvas.setFontAndSize(font, 7);
        canvas.setTextMatrix(x, y);
        canvas.showText(value);
        canvas.endText();
    }

    private static String[] code(String itm8, String ean, String name, String prio, String unit, String pack,
            String factor, String price, String removal, String deposit) {
        return new String[] {itm8, ean, name, prio, unit, pack, factor, price, removal, deposit, "", ""};
    }

    /** Multikody w układzie raportu z systemu sklepu: nagłówek, pary, pusty wiersz i "Ilość linii: : N". */
    private static byte[] productCodes(String storeNumber, String edited, int declaredRows, String[]... codes)
            throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"TRYTOMA  Sp. z o.o. - Numer punktu sprzedaży " + storeNumber});
        rows.add(new String[] {"Data edycji: " + edited});
        rows.add(new String[] {"Szczegóły informacji kodu sprzedaży"});
        rows.add(new String[] {"Kod artykułu", "Kod EAN13", "Nazwa", "Prio", "Jedn.Sprzedaży", "Opak.sprzedaż",
                "Współcz. ceny", "CenaSprzedaży", "Data usunięcia", "Kaucja", "E-Commerce", "Błąd E-Com"});
        rows.addAll(List.of(codes));
        rows.add(new String[] {""});
        rows.add(new String[] {"Ilość linii: : " + declaredRows});
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

    private ResultActions uploadPromotions(Cookie owner, String storeId, byte[] content) throws Exception {
        return mvc.perform(multipart(promotionsUrl(storeId))
                .file(new MockMultipartFile("file", "Taryfy.pdf", "application/pdf", content))
                .cookie(owner).with(csrf()));
    }

    private ResultActions uploadCodes(Cookie owner, String storeId, byte[] content) throws Exception {
        return mvc.perform(multipart(codesUrl(storeId))
                .file(new MockMultipartFile("file", "Multikody.xlsx", "application/octet-stream", content))
                .cookie(owner).with(csrf()));
    }

    private static String promotionsUrl(String storeId) {
        return "/api/stores/" + storeId + "/imports/promotions";
    }

    private static String codesUrl(String storeId) {
        return "/api/stores/" + storeId + "/imports/product-codes";
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep promocji\",\"active\":true}"))
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
