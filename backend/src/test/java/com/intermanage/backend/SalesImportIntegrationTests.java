package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.sales.entity.Sale;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class SalesImportIntegrationTests {

    private static final String[] HEADER = {"ITM8 Prio", "EAN Prio", "Nazwa", "Marka", "Data", "Ilość",
            "Wartosc FIFO", "Wartość ceny sprzedaży", "Wartość VAT", "Wart.Marza FIFO", "% Marża"};
    private static final String EAN = "5900983014894";

    @Autowired
    MockMvc mvc;

    @Autowired
    SaleRepository sales;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void savedSalesAreNeverChangedByLaterReports() throws Exception {
        Cookie owner = login();
        String storeNumber = storeNumber();
        String storeId = createStore(owner, storeNumber);

        upload(owner, storeId, report(storeNumber, "26/10/2026   Godzina: 15:03:27", "31/12/2025", "04/01/2026",
                row("0000000000453", "31/12/2025", "1", "5.2", "5.97", "0.28000000000000003", "0.49", "8.14"),
                row("0000000000453", "04/01/2026", "1", "5.2", "6.49", "0.31", "0.98", "15.11"),
                // Ten sam artykuł tego samego dnia pod drugim EAN-em to osobny wiersz.
                row("0000000000453", "5900983014900", "04/01/2026", "3", "15.6", "19.47", "0.93", "2.94", "15.11"),
                // Artykułu nie ma w kartotece: sprzedaż i tak jest zapisywana, łączy się po ITM8.
                row("0000099999999", "02/01/2026", "-2.5", "-10", "-12.6", "-0.6", "-2", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.createdCount").value(4))
                .andExpect(jsonPath("$.existingCount").value(0))
                .andExpect(jsonPath("$.periodFrom").value("2025-12-31"))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-10-26T15:03:27"));

        Sale sale = sale(storeId, LocalDate.of(2025, 12, 31), "0000000000453");
        assertThat(sale.getVatValue()).isEqualByComparingTo("0.28");
        assertThat(sale.getSaleValueNet()).isEqualByComparingTo("5.69");

        // Nowszy raport z innymi wartościami zapisanego dnia: dzień zostaje jak był, dopisuje się tylko nowy.
        upload(owner, storeId, report(storeNumber, "27/10/2026   Godzina: 08:00:00", "04/01/2026", "05/01/2026",
                row("0000000000453", "04/01/2026", "2", "10.4", "12.98", "0.62", "1.96", "15.11"),
                row("0000099999999", "05/01/2026", "1", "4", "5.04", "0.24", "0.8", "15.87")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.existingCount").value(1))
                .andExpect(jsonPath("$.conflictCount").value(1));
        Sale kept = sale(storeId, LocalDate.of(2026, 1, 4), "0000000000453");
        assertThat(kept.getQuantity()).isEqualByComparingTo("1");
        assertThat(kept.getSaleValueGross()).isEqualByComparingTo("6.49");
        assertThat(sales.findById(new Sale.Key(UUID.fromString(storeId), LocalDate.of(2026, 1, 4), "0000000000453",
                "5900983014900")).orElseThrow().getQuantity()).isEqualByComparingTo("3");
        // Raporty liczą całą sprzedaż ITM8 z dnia, ze wszystkich EAN-ów.
        Object[] article = (Object[]) entityManager.createNativeQuery("""
                        select quantity, sale_value_gross from sales_by_article
                        where store_id = :store and sale_date = date '2026-01-04' and itm8 = '0000000000453'
                        """)
                .setParameter("store", UUID.fromString(storeId)).getSingleResult();
        assertThat((BigDecimal) article[0]).isEqualByComparingTo("4");
        assertThat((BigDecimal) article[1]).isEqualByComparingTo("25.96");

        // Te same wartości: nic nowego i nic niezgodnego. Starszy raport może tylko dopisać brakujący dzień.
        upload(owner, storeId, report(storeNumber, "27/10/2026   Godzina: 09:00:00", "04/01/2026", "04/01/2026",
                row("0000000000453", "04/01/2026", "1", "5.2", "6.49", "0.31", "0.98", "15.11")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.existingCount").value(1))
                .andExpect(jsonPath("$.conflictCount").value(0));
        upload(owner, storeId, report(storeNumber, "25/10/2026   Godzina: 10:00:00", "02/01/2026", "03/01/2026",
                row("0000099999999", "02/01/2026", "9", "1", "1", "0", "0", "0"),
                row("0000099999999", "03/01/2026", "1", "4", "5.04", "0.24", "0.8", "15.87")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.conflictCount").value(1));
        assertThat(sale(storeId, LocalDate.of(2026, 1, 2), "0000099999999").getQuantity()).isEqualByComparingTo("-2.5");

        mvc.perform(get(importsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.rowCount").value(6))
                .andExpect(jsonPath("$.firstSaleDate").value("2025-12-31"))
                .andExpect(jsonPath("$.lastSaleDate").value("2026-01-05"))
                .andExpect(jsonPath("$.imports.length()").value(4));

        // Raport innego sklepu, data spoza okresu i powtórzony wiersz.
        upload(owner, storeId, report("10023", "28/10/2026   Godzina: 08:00:00", "01/01/2026", "04/01/2026",
                row("0000000000453", "04/01/2026", "9", "1", "1", "0", "0", "0")))
                .andExpect(status().isConflict());
        upload(owner, storeId, report(storeNumber, "28/10/2026   Godzina: 08:00:00", "01/01/2026", "04/01/2026",
                row("0000000000453", "04/01/2026", "9", "1", "1", "0", "0", "0"),
                row("0000000000453", "05/01/2026", "9", "1", "1", "0", "0", "0"),
                row("0000000000453", "04/01/2026", "9", "1", "1", "0", "0", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(2));
        // Bez ostatniego wiersza z liczbą wierszy plik może być ucięty.
        List<String[]> withoutFooter = reportLines(storeNumber, "28/10/2026   Godzina: 08:00:00", "01/01/2026",
                "04/01/2026", row("0000000000453", "03/01/2026", "9", "1", "1", "0", "0", "0"));
        upload(owner, storeId, workbook(withoutFooter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("liczbą wierszy")));

        // Także poza aplikacją zapisanej sprzedaży nie da się zmienić ani usunąć.
        UUID store = UUID.fromString(storeId);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager
                .createNativeQuery("update sales set quantity = 0 where store_id = :store")
                .setParameter("store", store).executeUpdate()))
                .hasStackTraceContaining("Zapisanej sprzedaży nie można zmieniać ani usuwać");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> entityManager
                .createNativeQuery("delete from sales where store_id = :store")
                .setParameter("store", store).executeUpdate()))
                .hasStackTraceContaining("Zapisanej sprzedaży nie można zmieniać ani usuwać");
        mvc.perform(get(importsUrl(storeId)).cookie(owner)).andExpect(jsonPath("$.rowCount").value(6));
        assertThat(sale(storeId, LocalDate.of(2026, 1, 4), "0000000000453").getQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void dayOfReportGenerationIsIncompleteAndAddedByNextDaysReport() throws Exception {
        Cookie owner = login();
        String storeNumber = storeNumber();
        String storeId = createStore(owner, storeNumber);

        // Raport wygenerowany 26.10 w ciągu dnia: sprzedaż z 26.10 jest niepełna, więc nie jest zapisywana.
        upload(owner, storeId, report(storeNumber, "26/10/2026   Godzina: 15:06:23", "25/10/2026", "26/10/2026",
                row("0000000000453", "25/10/2026", "1", "5.2", "5.97", "0.28", "0.49", "8.14"),
                row("0000000000453", "26/10/2026", "1", "5.2", "5.97", "0.28", "0.49", "8.14"),
                row("0000000000454", "26/10/2026", "2", "5.2", "5.97", "0.28", "0.49", "8.14")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.existingCount").value(0))
                .andExpect(jsonPath("$.incompleteCount").value(2));
        assertThat(sales.findById(new Sale.Key(UUID.fromString(storeId), LocalDate.of(2026, 10, 26), "0000000000453",
                EAN))).isEmpty();

        // Raport z 27.10 ma już pełny 26.10: ten dzień się dopisuje.
        upload(owner, storeId, report(storeNumber, "27/10/2026   Godzina: 07:30:00", "25/10/2026", "26/10/2026",
                row("0000000000453", "25/10/2026", "1", "5.2", "5.97", "0.28", "0.49", "8.14"),
                row("0000000000453", "26/10/2026", "4", "20.8", "23.88", "1.12", "1.96", "8.14"),
                row("0000000000454", "26/10/2026", "3", "5.2", "5.97", "0.28", "0.49", "8.14")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.existingCount").value(1))
                .andExpect(jsonPath("$.incompleteCount").value(0));
        assertThat(sale(storeId, LocalDate.of(2026, 10, 26), "0000000000453").getQuantity()).isEqualByComparingTo("4");
        mvc.perform(get(importsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.lastSaleDate").value("2026-10-26"));
    }

    @Test
    void importsManyBatchesAndRollsBackWholeFileOnError() throws Exception {
        Cookie owner = login();
        String storeNumber = storeNumber();
        String storeId = createStore(owner, storeNumber);
        List<String[]> rows = new ArrayList<>();
        for (int article = 0; article < 1_200; article++) {
            for (int day = 1; day <= 10; day++) {
                rows.add(row(String.format("%013d", 1_000_000 + article), String.format("%02d/03/2026", day),
                        "1", "2", "3.15", "0.15", "1", "33.33"));
            }
        }
        // Błąd w ostatnim wierszu, po zapisaniu dwóch paczek: nic nie może zostać w bazie.
        List<String[]> broken = new ArrayList<>(rows);
        broken.add(row("0000001000000", "11/03/2026", "x", "2", "3.15", "0.15", "1", "33.33"));
        upload(owner, storeId, report(storeNumber, "26/10/2026   Godzina: 15:03:27", "01/03/2026", "11/03/2026",
                broken.toArray(String[][]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));
        mvc.perform(get(importsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.imports.length()").value(0));

        upload(owner, storeId, report(storeNumber, "26/10/2026   Godzina: 15:03:27", "01/03/2026", "10/03/2026",
                rows.toArray(String[][]::new)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(12_000))
                .andExpect(jsonPath("$.createdCount").value(12_000));
        assertThat(sales.count()).isGreaterThanOrEqualTo(12_000);
        assertThat(sale(storeId, LocalDate.of(2026, 3, 10), "0000001001199").getSaleValueNet()).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    private Sale sale(String storeId, LocalDate date, String itm8) {
        return sales.findById(new Sale.Key(UUID.fromString(storeId), date, itm8, EAN)).orElseThrow();
    }

    private ResultActions upload(Cookie owner, String storeId, byte[] content) throws Exception {
        return mvc.perform(multipart(importsUrl(storeId))
                .file(new MockMultipartFile("file", "sprzedaz.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                .cookie(owner).with(csrf()));
    }

    private static String importsUrl(String storeId) {
        return "/api/stores/" + storeId + "/imports/sales";
    }

    private static String storeNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep sprzedaży\",\"active\":true}"))
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

    private static String[] row(String itm8, String date, String quantity, String cost, String gross, String vat,
            String margin, String marginPercent) {
        return row(itm8, EAN, date, quantity, cost, gross, vat, margin, marginPercent);
    }

    private static String[] row(String itm8, String ean, String date, String quantity, String cost, String gross,
            String vat, String margin, String marginPercent) {
        // "96 LINIA" w nazwie nie może wyglądać jak stopka z liczbą linii.
        return new String[] {itm8, ean, "BRULION A4 96 LINIA PSH", "", date, quantity, cost,
                gross, vat, margin, marginPercent};
    }

    /**
     * Raport w układzie z prawdziwego pliku (sprzedaz.xlsx, "baza od … do ….xlsx"): nagłówek sklepu, opis, zakres
     * dat, kolumny, wiersze, suma z pustym ITM8 i na końcu "Ilość linii:N" w kolumnie ITM8.
     */
    private static byte[] report(String storeNumber, String generated, String from, String to, String[]... rows)
            throws IOException {
        List<String[]> all = reportLines(storeNumber, generated, from, to, rows);
        all.add(new String[] {"", "", "", "", "", "1604606", "7701358.01", "11018729.21", "1080600.95", "2236770.25",
                "20.3"});
        all.add(new String[] {});
        all.add(new String[] {"Ilość linii:" + rows.length});
        return workbook(all);
    }

    private static List<String[]> reportLines(String storeNumber, String generated, String from, String to,
            String[]... rows) {
        List<String[]> all = new ArrayList<>();
        all.add(new String[] {"PDV: " + storeNumber + " - TRYTOMA  Sp. z o.o.                    Data: " + generated});
        all.add(new String[] {"Zestawienie : Wejścia/Wyjścia: Poziom szczegółu: wg.artykułu, Szczegóły Zakres: wg.dnia, "
                + "Ruch: Sprzedaż po rabacie, Typ wyceny: FIFO"});
        all.add(new String[] {});
        all.add(new String[] {"Wybór Danych:  Od " + from + " do " + to});
        all.add(new String[] {"Kryteria wyboru:"});
        all.add(new String[] {});
        all.add(HEADER);
        all.addAll(List.of(rows));
        return all;
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
        shared.forEach(s -> strings.append("<si><t xml:space=\"preserve\">").append(s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")).append("</t></si>"));
        strings.append("</sst>");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putEntry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Arkusz1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
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
