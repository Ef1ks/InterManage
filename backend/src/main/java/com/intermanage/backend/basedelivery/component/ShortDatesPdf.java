package com.intermanage.backend.basedelivery.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Raport krótkich terminów dostawy z bazy (A4 pionowo): towar, który przyjechał z terminem krótszym, niż baza ma
 * zostawić sklepowi (dzień dostawy + dni dla sklepu). Kartka dla osoby, która wyjaśnia to z bazą: bon, nośnik,
 * artykuł, ilość, termin na towarze, termin wymagany i ile dni brakuje, na końcu miejsce na podpisy.
 */
@Component
public class ShortDatesPdf {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(200, 200, 200);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final Color ALERT = new Color(254, 202, 202);

    private static final String[] HEADERS = { "Lp.", "Nośnik", "ITM8", "EAN", "Artykuł", "Ilość", "Termin",
            "Wymagany\nod", "Brakuje\ndni" };
    private static final float[] WIDTHS = { 22, 52, 46, 70, 150, 34, 50, 50, 36 };

    private final BaseFont regular;
    private final BaseFont bold;

    public ShortDatesPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public record Report(String storeLabel, String number, LocalDate deliveryDate, List<Line> lines) {
    }

    public record Line(String carrier, String itm8, String ean, String name, BigDecimal quantity,
            LocalDate bestBefore, LocalDate required) {

        long missingDays() {
            return ChronoUnit.DAYS.between(bestBefore, required);
        }
    }

    public byte[] pdf(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 32, 32, 36, 48);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 8, MUTED), "Krótkie terminy · bon " + report.number()
                    + " · " + report.storeLabel() + " · wydruk " + PRINTED.format(LocalDateTime.now(WARSAW))));
            document.addTitle("Krótkie terminy - bon " + report.number());
            document.open();

            Paragraph title = new Paragraph("Towar z krótkim terminem", font(bold, 16, INK));
            title.setSpacingAfter(4);
            document.add(title);
            Paragraph subtitle = new Paragraph("Dostawa z bazy, bon nr " + report.number() + " z dnia "
                    + DATE.format(report.deliveryDate()) + " · " + report.storeLabel(), font(regular, 10, INK));
            subtitle.setSpacingAfter(4);
            document.add(subtitle);
            Paragraph note = new Paragraph("Termin przydatności krótszy niż dzień dostawy + liczba dni, które baza ma "
                    + "zostawić sklepowi. Ilość w sztukach przyjętych.", font(regular, 8.5f, MUTED));
            note.setSpacingAfter(12);
            document.add(note);

            if (report.lines().isEmpty()) {
                document.add(new Paragraph("Wszystkie artykuły dostawy mają wymagany termin.", font(regular, 10, INK)));
            } else {
                document.add(table(report.lines()));
            }
            document.add(signatures());
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF krótkich terminów", e);
        }
        return out.toByteArray();
    }

    private PdfPTable table(List<Line> lines) throws DocumentException {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : HEADERS) {
            PdfPCell cell = cell(new Phrase(header, font(bold, 8, INK)), Element.ALIGN_LEFT);
            cell.setBackgroundColor(HEAD);
            table.addCell(cell);
        }
        int index = 1;
        for (Line line : lines) {
            Font text = font(regular, 8.5f, INK);
            table.addCell(cell(new Phrase(String.valueOf(index++), text), Element.ALIGN_RIGHT));
            table.addCell(cell(new Phrase(line.carrier(), text), Element.ALIGN_LEFT));
            table.addCell(cell(new Phrase(line.itm8(), text), Element.ALIGN_LEFT));
            table.addCell(cell(new Phrase(line.ean() == null ? "" : line.ean(), text), Element.ALIGN_LEFT));
            table.addCell(cell(new Phrase(line.name(), text), Element.ALIGN_LEFT));
            table.addCell(cell(new Phrase(quantity(line.quantity()), text), Element.ALIGN_RIGHT));
            PdfPCell bestBefore = cell(new Phrase(DATE.format(line.bestBefore()), font(bold, 8.5f, INK)),
                    Element.ALIGN_LEFT);
            bestBefore.setBackgroundColor(ALERT);
            table.addCell(bestBefore);
            table.addCell(cell(new Phrase(DATE.format(line.required()), text), Element.ALIGN_LEFT));
            table.addCell(cell(new Phrase(String.valueOf(line.missingDays()), font(bold, 8.5f, INK)),
                    Element.ALIGN_RIGHT));
        }
        return table;
    }

    private PdfPTable signatures() throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 1 });
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        for (String label : new String[] { "Przyjął (sklep)", "Kierowca / baza" }) {
            PdfPCell cell = new PdfPCell(new Phrase("\n\n..............................................\n" + label,
                    font(regular, 8.5f, MUTED)));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        table.setKeepTogether(true);
        return table;
    }

    private static PdfPCell cell(Phrase phrase, int align) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(RULE);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(4);
        cell.setPaddingLeft(3);
        cell.setPaddingRight(3);
        return cell;
    }

    private static String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
