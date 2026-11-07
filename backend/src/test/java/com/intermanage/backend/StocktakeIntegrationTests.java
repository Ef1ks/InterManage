package com.intermanage.backend;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ThreadLocalRandom;

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
class StocktakeIntegrationTests {

    private static final String WHOLE_STORE = "ITM8 : 0000000000000  - 9999999999999";
    private static final String DEPARTMENT = "NOMENKLATURA : 01 - 01";
    private static final float[] X = {33, 101.5f, 159, 338.6f, 380, 418, 460, 500, 541};

    @Autowired
    MockMvc mvc;

    @Autowired
    PdfFonts fonts;

    private Cookie owner;
    private String storeId;
    private String storeNumber;

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        storeNumber = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 999_999));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + storeNumber + "\",\"name\":\"Sklep inwentury\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        storeId = JsonPath.read(json, "$.id");
    }

    private String path() {
        return "/api/stores/" + storeId + "/stocktakes";
    }

    @Test
    void findsPairsAndMergesDepartmentsAndResolvesAfterWholeStoreReport() throws Exception {
        String id = upload("porownanie.pdf", report(WHOLE_STORE, "30/06/2026 - 06:58:24", "-24,00",
                row("0000000000011", "5900000000011", "PIWO KOZEL 0.5L PUSZKA - KOZEL", null, "42,000", "10,000", "32,000", "160,000", null),
                row("0000000000012", "5900000000012", "PIWO KOZEL 0.5L PUSZKA CIEMNE - KOZEL", null, "10,000", "42,000", "-32,000", "-160,000", "Stan"),
                row("0000000000013", "5900000000013", "BATON Z ORZECHAMI 40G - INNA", "BS", "0,000", "20,000", "-20,000", "-40,000", null),
                row("0000000000014", "5900000000014", "WODA GAZOWANA 1L - NALECZ", null, "5,000", "-3,000", "8,000", "16,000", "1sza inwent")));

        mvc.perform(get(path()).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("INWENT POLROCZ 2026"))
                .andExpect(jsonPath("$[0].lineCount").value(4));

        mvc.perform(get(path() + "/" + id).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(4))
                .andExpect(jsonPath("$.pairs", hasSize(1)))
                .andExpect(jsonPath("$.pairs[0].plusItm8").value("0000000000011"))
                .andExpect(jsonPath("$.pairs[0].minusItm8").value("0000000000012"))
                .andExpect(jsonPath("$.pairs[0].strength").value("SWAP_STRONG"))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000013')].reasons[0]").value(hasItem("NOT_COUNTED")))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000013')].blockType").value(hasItem("BS")))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000014')].bucket").value(hasItem("SYSTEM_STOCK")));

        // Wydruk działu poprawia jeden artykuł, ale o reszcie nic nie mówi.
        upload("dzial.pdf", report(DEPARTMENT, "30/06/2026 - 10:00:00", "-140,00",
                row("0000000000013", "5900000000013", "BATON Z ORZECHAMI 40G - INNA", null, "18,000", "20,000", "-2,000", "-140,000", null)));
        mvc.perform(get(path() + "/" + id).cookie(owner))
                .andExpect(jsonPath("$.lineCount").value(4))
                .andExpect(jsonPath("$.resolvedCount").value(0))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000013')].value").value(hasItem(-140.0)))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000013')].firstValue").value(hasItem(-40.0)));

        // Starszy wydruk wgrany później niczego nie cofa.
        upload("stary.pdf", report(DEPARTMENT, "30/06/2026 - 08:00:00", "-38,00",
                row("0000000000013", "5900000000013", "BATON Z ORZECHAMI 40G - INNA", null, "1,000", "20,000", "-19,000", "-38,000", null)));
        mvc.perform(get(path() + "/" + id).cookie(owner))
                .andExpect(jsonPath("$.lines[?(@.itm8 == '0000000000013')].value").value(hasItem(-140.0)));

        // Raport całego sklepu pokazuje tylko rozbieżności: artykułów, których w nim nie ma, różnica już nie dotyczy.
        upload("nowy.pdf", report(WHOLE_STORE, "01/07/2026 - 09:00:00", "16,00",
                row("0000000000014", "5900000000014", "WODA GAZOWANA 1L - NALECZ", null, "5,000", "-3,000", "8,000", "16,000", null)));
        mvc.perform(get(path() + "/" + id).cookie(owner))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.resolvedCount").value(3))
                .andExpect(jsonPath("$.pairs", hasSize(0)))
                .andExpect(jsonPath("$.imports", hasSize(4)));
    }

    @Test
    void rejectsReportWithWrongTotalsOrFromAnotherStore() throws Exception {
        send("zly.pdf", report(WHOLE_STORE, "30/06/2026 - 06:58:24", "99,00",
                row("0000000000021", "5900000000021", "SOK 1L - TYMBARK", null, "1,000", "2,000", "-1,000", "-3,000", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("nie zgadza się")));

        storeNumber = "99999";
        send("obcy.pdf", report(WHOLE_STORE, "30/06/2026 - 06:58:24", "-3,00",
                row("0000000000021", "5900000000021", "SOK 1L - TYMBARK", null, "1,000", "2,000", "-1,000", "-3,000", null)))
                .andExpect(status().isConflict());
    }

    @Test
    void deletedStocktakeCanBeRestored() throws Exception {
        String id = upload("porownanie.pdf", report(WHOLE_STORE, "30/06/2026 - 06:58:24", "-3,00",
                row("0000000000021", "5900000000021", "SOK 1L - TYMBARK", null, "1,000", "2,000", "-1,000", "-3,000", null)));
        mvc.perform(delete(path() + "/" + id).cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path()).cookie(owner)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(path() + "/" + id).cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(post(path() + "/" + id + "/restore").cookie(owner).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path()).cookie(owner)).andExpect(jsonPath("$[*].id", containsInAnyOrder(id)));
    }

    private String upload(String name, byte[] content) throws Exception {
        String json = send(name, content).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.stocktakeId");
    }

    private ResultActions send(String name, byte[] content) throws Exception {
        return mvc.perform(multipart(path())
                .file(new MockMultipartFile("file", name, "application/pdf", content))
                .cookie(owner).with(csrf()));
    }

    private static String[] row(String... cells) {
        return cells;
    }

    /** PDF w układzie raportu: każda komórka to osobny napis, na końcu stopka z liczbą linii i wyceny z sumą. */
    private byte[] report(String selection, String generated, String sum, String[]... rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(document, bytes);
        document.open();
        BaseFont font = fonts.regular();
        PdfContentByte canvas = writer.getDirectContent();
        text(canvas, font, 20, 817, "mar. " + generated + " Strona: 1 / 2");
        text(canvas, font, 22, 802, "TRYTOMA  Sp. z o.o. : " + storeNumber);
        text(canvas, font, 22, 791, "PORÓWNANIE STANÓW RZECZYWISTYCH I TEORETYCZNYCH WG.ART.");
        text(canvas, font, 22, 780, "INWENT POLROCZ 2026  Numer : 100  22/06/2026 - Z art. wyzerowanymi");
        text(canvas, font, 195, 752, "Ramka wyboru - WG ITM8 (posort. wg. Nomen/Ranga/ITM8 prio)");
        text(canvas, font, 230, 745, selection);
        text(canvas, font, 357, 727, "St.Rzecz.Il.(JS)");
        text(canvas, font, 481, 727, "Niezg.Wart.Zak.");
        text(canvas, font, 46, 723, "ITM8");
        float y = 710;
        for (String[] row : rows) {
            for (int column = 0; column < row.length; column++) {
                if (row[column] != null) {
                    text(canvas, font, X[column], y, row[column]);
                }
            }
            y -= 10;
        }
        text(canvas, font, 22, y - 10, rows.length + " linie");
        document.newPage();
        canvas = writer.getDirectContent();
        text(canvas, font, 20, 817, "mar. " + generated + " Strona: 2 / 2");
        text(canvas, font, 22, 752, "Wycena w cenach zakupu");
        text(canvas, font, 304, 752, "Wycena w cenach sprzedaży");
        text(canvas, font, 22, 690, "SUMA");
        text(canvas, font, 129, 691, sum);
        text(canvas, font, 195, 691, "0,00");
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
}
