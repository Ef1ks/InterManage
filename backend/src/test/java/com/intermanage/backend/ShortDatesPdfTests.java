package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.basedelivery.component.ShortDatesPdf;
import com.intermanage.backend.basedelivery.component.ShortDatesPdf.Line;
import com.intermanage.backend.common.component.PdfFonts;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/**
 * Wydruk krótkich terminów: numery (nośnik, ITM8, EAN) i daty stoją w jednej linii, a u góry są nr BL i standardowe
 * DLV. {@code -DargLine=-Dshortdates.pdf.out=<folder>} zapisuje próbkę.
 */
class ShortDatesPdfTests {

    private final ShortDatesPdf pdf = new ShortDatesPdf(new PdfFonts());

    @Test
    void keepsNumbersAndDatesInOneLine() throws IOException {
        LocalDate delivery = LocalDate.of(2026, 11, 17);
        List<Line> lines = List.of(
                line("6134334", "5900531000202", "SEREK WIEJSKI SZCZYP.150G", 48, "2026-11-22", 6),
                line("6137570", "5905670197279", "PAROWKI Z PIERSI KURCZAKA 200G", 44, "2026-11-25", 12),
                line("6166169", "5901009005926", "CIN&CIN FR SP BL0.75L0 BEZA", 6, "2027-03-31", 999));
        byte[] content = pdf.pdf(new ShortDatesPdf.Report("10023 Zielona Góra - Szosa Kisielińska 22", "09516052459",
                delivery, 6, lines));
        String out = System.getProperty("shortdates.pdf.out");
        if (out != null) Files.write(Path.of(out, "krotkie-terminy.pdf"), content);

        PdfReader reader = new PdfReader(content);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("09516052459", "5900531000202", "5905670197279", "22.11.2026", "Standardowo DLV",
                "6 dni");
    }

    private static Line line(String itm8, String ean, String name, int quantity, String bestBefore, int dlv) {
        LocalDate delivery = LocalDate.of(2026, 11, 17);
        return new Line("42923282", itm8, ean, name, BigDecimal.valueOf(quantity), LocalDate.parse(bestBefore), dlv,
                delivery.plusDays(dlv));
    }
}
