package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.cash.component.CashPdf;
import com.intermanage.backend.cash.entity.CashDocumentKind;
import com.intermanage.backend.common.component.PdfFonts;

/** Wydruki kasy powstają z pieczątką i bez niej; z {@code -Dcash.pdf.out=<folder>} zapisują się do obejrzenia. */
class CashPdfTests {

    private final CashPdf pdf = new CashPdf(new PdfFonts());

    @Test
    void printsVoucherAndReport() throws Exception {
        byte[] stamp = stamp();
        LocalDate day = LocalDate.of(2026, 11, 13);
        byte[] kp = pdf.voucher(new CashPdf.Voucher(CashDocumentKind.KP, "KP 22/11/2026", day, new BigDecimal("88"),
                "Utarg z dnia", "ZDRAPKOMAT", null, null, "Anna Nowak", stamp,
                "Sklep 10023, Szosa Kisielińska 22, Zielona Góra", false, null, "RK 13/11/2026", 7));
        byte[] kw = pdf.voucher(new CashPdf.Voucher(CashDocumentKind.KW, "KW 5/11/2026", day, new BigDecimal("96"),
                "Zwrot gotówki", "WYRÓWNANIE RÓŻNICY KP 7/11/2026 Z DNIA 02.11.2026", "Jan Kowalski",
                "ul. Przykładowa 1, Zielona Góra", "Anna Nowak", null, "Sklep 10023", true, "zła kwota", null, null));
        byte[] report = pdf.report(new CashPdf.Report("RK 13/11/2026", day, day, new BigDecimal("136228.80"),
                new BigDecimal("47216.70"), new BigDecimal("110096.00"), new BigDecimal("73349.50"), 5, 2, List.of(
                        new CashPdf.ReportRow(1, day, "KW 4/11/2026", "Zdanie utargu DO BANKU", null, null,
                                new BigDecimal("110000"), false),
                        new CashPdf.ReportRow(2, day, "KW 5/11/2026",
                                "Zwrot gotówki WYRÓWNANIE RÓŻNICY KP 7/11/2026 Z DNIA 02.11.2026", "Jan Kowalski",
                                null, new BigDecimal("96"), false),
                        new CashPdf.ReportRow(3, day, "KP 18/11/2026", "Utarg z dnia STACJA PALIW 1 ZMIANA", null,
                                new BigDecimal("14712.95"), null, false),
                        new CashPdf.ReportRow(4, day, "KP 19/11/2026", "Utarg z dnia LOTTO", null, new BigDecimal("80"),
                                null, true)),
                stamp, "Sklep 10023, Szosa Kisielińska 22", "Anna Nowak", LocalDateTime.of(2026, 11, 13, 18, 30)));
        for (byte[] file : List.of(kp, kw, report)) {
            assertThat(new String(file, 0, 5)).isEqualTo("%PDF-");
        }
        String out = System.getProperty("cash.pdf.out");
        if (out != null) {
            Files.write(Path.of(out, "kp.pdf"), kp);
            Files.write(Path.of(out, "kw.pdf"), kw);
            Files.write(Path.of(out, "raport.pdf"), report);
        }
    }

    private static byte[] stamp() throws Exception {
        BufferedImage image = new BufferedImage(420, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 420, 160);
        graphics.setColor(new Color(30, 60, 160));
        graphics.drawRect(6, 6, 406, 146);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        graphics.drawString("FIRMA SP. Z O.O.", 30, 55);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
        graphics.drawString("ul. Przykładowa 1, 65-001 Miasto", 30, 95);
        graphics.drawString("NIP 000-000-00-00", 30, 130);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
