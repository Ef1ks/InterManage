package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
class NomenclatureImportIntegrationTests {

    private static final String[] HEADER = {"Kod Nomenklatury", "Nazwa Nomenklatury", "Kod VAT", "Jednostka miary",
            "Ilość etyk.\"Gondola\"", "Format etykiet", "Typ Przejść do Kas", "Typ sprzedaży", "St.Mag./Reappro"};
    /** Lewe krawędzie kolumn jak w raporcie z systemu sklepu. */
    private static final float[] COLUMNS = {22, 95, 244, 287, 353, 435, 520, 636, 741};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PdfFonts fonts;

    @Test
    void importsNomenclatureFromPdfAndMergesByCode() throws Exception {
        Cookie owner = login();
        String storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String storeId = createStore(owner, storeNumber);

        upload(owner, storeId, report(storeNumber, "26/10/2026 - 14:43:41",
                row("01", "01_DELIKATESY", "kg", "St.Mag."),
                row("0101", "PIECZYWO CHRUPKIE", "kg", "St.Mag."),
                row("010101", "PIECZYWO TWARDE", "kg", "St.Mag."),
                row("010105", "PIECZYWO RYŻOWE - INNE", "kg", "Bez zarzadzania"),
                row("99", "99_śmietnik", "szt.", "St.Mag.")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.createdCount").value(5))
                .andExpect(jsonPath("$.reportGeneratedAt").value("2026-10-26T14:43:41"));

        assertThat(jdbc.queryForMap("""
                select n.name, n.level, n.vat_code, n.unit, n.label_format, n.till_mode, n.sale_type, n.stock_mode,
                       p.code as parent_code
                from nomenclatures n join nomenclatures p on p.id = n.parent_id
                where n.store_id = ?::uuid and n.code = '010105'""", storeId))
                .containsEntry("name", "PIECZYWO RYŻOWE - INNE")
                .containsEntry("level", "SUBFAMILY")
                .containsEntry("vat_code", 1)
                .containsEntry("unit", "kg")
                .containsEntry("label_format", 43)
                .containsEntry("till_mode", "Podać cenę")
                .containsEntry("sale_type", "Samoobsługa")
                .containsEntry("stock_mode", "Bez zarzadzania")
                .containsEntry("parent_code", "0101");

        // Nowszy raport: zmieniona nazwa, nowa podrodzina, a brakująca pozycja (99) zostaje w bazie.
        upload(owner, storeId, report(storeNumber, "27/10/2026 - 08:00:00",
                row("01", "01_DELIKATESY", "kg", "St.Mag."),
                row("0101", "PIECZYWO CHRUPKIE", "kg", "St.Mag."),
                row("010101", "PIECZYWO TWARDE I CHRUPKIE", "kg", "St.Mag."),
                row("010102", "PIECZYWO LEKKIE", "kg", "St.Mag."),
                row("010105", "PIECZYWO RYŻOWE - INNE", "kg", "Bez zarzadzania")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1));
        assertThat(jdbc.queryForObject("select name from nomenclatures where store_id = ?::uuid and code = '010101'",
                String.class, storeId)).isEqualTo("PIECZYWO TWARDE I CHRUPKIE");

        mvc.perform(get(importsUrl(storeId)).cookie(owner))
                .andExpect(jsonPath("$.regionCount").value(2))
                .andExpect(jsonPath("$.familyCount").value(1))
                .andExpect(jsonPath("$.subfamilyCount").value(3))
                .andExpect(jsonPath("$.imports.length()").value(2));

        // Starszy raport, raport innego sklepu, podrodzina bez rodziny i niezgodna liczba linii.
        upload(owner, storeId, report(storeNumber, "25/10/2026 - 08:00:00", row("01", "01_DELIKATESY", "kg", "St.Mag.")))
                .andExpect(status().isConflict());
        upload(owner, storeId, report("10023", "28/10/2026 - 08:00:00", row("01", "01_DELIKATESY", "kg", "St.Mag.")))
                .andExpect(status().isConflict());
        upload(owner, storeId, report(storeNumber, "28/10/2026 - 08:00:00",
                row("01", "01_DELIKATESY", "kg", "St.Mag."),
                row("020101", "BEZ RODZINY", "kg", "St.Mag.")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rowErrorCount").value(1));
        upload(owner, storeId, pdf(storeNumber, "28/10/2026 - 08:00:00", 3,
                List.<String[]>of(row("01", "01_DELIKATESY", "kg", "St.Mag."))))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(importsUrl(storeId))
                        .file(new MockMultipartFile("file", "nomenklatury.pdf", "application/pdf", "nie pdf".getBytes()))
                        .cookie(owner).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static String[] row(String code, String name, String unit, String stockMode) {
        return new String[] {code, name, "1", unit, "1", "43", "Podać cenę", "Samoobsługa", stockMode};
    }

    private byte[] report(String storeNumber, String generated, String[]... rows) throws Exception {
        return pdf(storeNumber, generated, rows.length, List.of(rows));
    }

    /**
     * PDF w układzie raportu z systemu sklepu: strona A4 obrócona do poziomu, każda komórka tabeli to osobny napis,
     * dwa wiersze na stronę (żeby sprawdzić czytanie wielu stron), na ostatniej stronie "N linie".
     */
    private byte[] pdf(String storeNumber, String generated, int declaredRows, List<String[]> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate());
        PdfWriter writer = PdfWriter.getInstance(document, bytes);
        document.open();
        BaseFont font = fonts.regular();
        List<List<String[]>> pages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += 2) {
            pages.add(rows.subList(i, Math.min(i + 2, rows.size())));
        }
        for (int page = 0; page < pages.size(); page++) {
            if (page > 0) {
                document.newPage();
            }
            PdfContentByte canvas = writer.getDirectContent();
            float top = PageSize.A4.rotate().getHeight();
            text(canvas, font, 20, top - 25, "lun. " + generated);
            text(canvas, font, 786, top - 25, "Strona: " + (page + 1) + " / " + pages.size());
            text(canvas, font, 20, top - 45, "TRYTOMA  Sp. z o.o. : " + storeNumber);
            text(canvas, font, 20, top - 55, "LISTA NOMENKLATUR");
            for (int column = 0; column < HEADER.length; column++) {
                text(canvas, font, COLUMNS[column] + 6, top - 68, HEADER[column]);
            }
            float y = top - 78;
            for (String[] row : pages.get(page)) {
                for (int column = 0; column < row.length; column++) {
                    text(canvas, font, COLUMNS[column], y, row[column]);
                }
                y -= 10;
            }
            if (page == pages.size() - 1) {
                text(canvas, font, 22, y - 10, declaredRows + " linie");
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

    private ResultActions upload(Cookie owner, String storeId, byte[] content) throws Exception {
        return mvc.perform(multipart(importsUrl(storeId))
                .file(new MockMultipartFile("file", "nomenklatury.pdf", "application/pdf", content))
                .cookie(owner).with(csrf()));
    }

    private static String importsUrl(String storeId) {
        return "/api/stores/" + storeId + "/imports/nomenclatures";
    }

    private String createStore(Cookie owner, String number) throws Exception {
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep nomenklatury\",\"active\":true}"))
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
