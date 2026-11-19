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
import com.lowagie.text.Chunk;
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
 * zostawić sklepowi (dzień dostawy + dni dla sklepu, DLV). Kartka dla osoby, która wyjaśnia to z bazą: nr BL, data i
 * standardowe DLV w ramkach u góry, potem nośnik, artykuł, ilość, termin na towarze, DLV artykułu, termin wymagany i
 * ile dni brakuje, na końcu miejsce na podpisy. Numery i daty nigdy się nie łamią.
 */
@Component
public class ShortDatesPdf {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(105, 105, 105);
    private static final Color RULE = new Color(212, 212, 216);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final Color ZEBRA = new Color(250, 250, 250);
    private static final Color ALERT = new Color(254, 202, 202);
    private static final Color OTHER_DLV = new Color(253, 230, 138);

    private static final float TEXT = 8;

    private static final String[] HEADERS = { "Lp.", "Nośnik", "ITM8", "EAN", "Artykuł", "Ilość", "Termin", "DLV",
            "Wymagany od", "Brakuje" };
    /** Szerokości w punktach (531 pt na A4 z marginesami): kolumny liczb i dat mieszczą cały numer w jednej linii. */
    private static final float[] WIDTHS = { 20, 48, 48, 72, 129, 32, 56, 26, 56, 44 };
    private static final int[] ALIGN = { Element.ALIGN_RIGHT, Element.ALIGN_LEFT, Element.ALIGN_LEFT,
            Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_CENTER, Element.ALIGN_RIGHT,
            Element.ALIGN_CENTER, Element.ALIGN_RIGHT };

    private final BaseFont regular;
    private final BaseFont bold;

    public ShortDatesPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code standardDlv}: najczęstsze dni dla sklepu w całym dokumencie, {@code null} gdy plik ich nie ma. */
    public record Report(String storeLabel, String number, LocalDate deliveryDate, Integer standardDlv,
            List<Line> lines) {
    }

    public record Line(String carrier, String itm8, String ean, String name, BigDecimal quantity,
            LocalDate bestBefore, Integer daysForStore, LocalDate required) {

        long missingDays() {
            return ChronoUnit.DAYS.between(bestBefore, required);
        }
    }

    public byte[] pdf(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 32, 32, 34, 48);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7.5f, MUTED), "Krótkie terminy · BL " + report.number()
                    + " · " + report.storeLabel() + " · wydruk " + PRINTED.format(LocalDateTime.now(WARSAW))));
            document.addTitle("Krótkie terminy - BL " + report.number());
            document.open();

            Paragraph title = new Paragraph("Towar z krótkim terminem", font(bold, 16, INK));
            title.setSpacingAfter(2);
            document.add(title);
            Paragraph store = new Paragraph("Dostawa z bazy · " + report.storeLabel(), font(regular, 9, MUTED));
            store.setSpacingAfter(10);
            document.add(store);
            document.add(facts(report));
            Paragraph note = new Paragraph("Termin przydatności krótszy niż dzień dostawy + dni dla sklepu (DLV). "
                    + "Ilość w sztukach przyjętych. Owoce i warzywa (1401) są pominięte. Żółte DLV: inne niż "
                    + "standardowo w tym dokumencie.", font(regular, 7.5f, MUTED));
            note.setSpacingBefore(8);
            note.setSpacingAfter(8);
            document.add(note);

            if (report.lines().isEmpty()) {
                document.add(new Paragraph("Wszystkie artykuły dostawy mają wymagany termin.", font(regular, 10, INK)));
            } else {
                document.add(table(report));
            }
            document.add(signatures());
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF krótkich terminów", e);
        }
        return out.toByteArray();
    }

    /** Ramki z danymi do wpisania w reklamacji: nr BL, data dostawy, standardowe DLV, liczba pozycji. */
    private PdfPTable facts(Report report) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1.5f, 1.1f, 1.1f, 0.9f });
        table.setWidthPercentage(100);
        table.addCell(fact("Nr BL", report.number()));
        table.addCell(fact("Dostawa", DATE.format(report.deliveryDate())));
        table.addCell(fact("Standardowo DLV",
                report.standardDlv() == null ? "–" : report.standardDlv() + " " + days(report.standardDlv())));
        table.addCell(fact("Pozycje", String.valueOf(report.lines().size())));
        return table;
    }

    private PdfPCell fact(String label, String value) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(label + "\n", font(regular, 7.5f, MUTED)));
        phrase.add(new Chunk(value, font(bold, 13, INK)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setBackgroundColor(HEAD);
        cell.setLeading(0, 1.15f);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(7);
        cell.setPaddingLeft(7);
        cell.setPaddingRight(7);
        cell.setNoWrap(true);
        return cell;
    }

    private PdfPTable table(Report report) throws DocumentException {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setTotalWidth(WIDTHS_TOTAL);
        table.setLockedWidth(true);
        table.setHeaderRows(1);
        for (int i = 0; i < HEADERS.length; i++) {
            PdfPCell cell = cell(new Phrase(HEADERS[i], font(bold, 7.5f, INK)), ALIGN[i], false);
            cell.setBackgroundColor(HEAD);
            table.addCell(cell);
        }
        int index = 1;
        for (Line line : report.lines()) {
            Color background = index % 2 == 0 ? ZEBRA : null;
            Font text = font(regular, TEXT, INK);
            Font strong = font(bold, TEXT, INK);
            boolean otherDlv = report.standardDlv() != null && line.daysForStore() != null
                    && !line.daysForStore().equals(report.standardDlv());
            PdfPCell[] cells = {
                    cell(new Phrase(String.valueOf(index), text), ALIGN[0], true),
                    cell(new Phrase(line.carrier(), text), ALIGN[1], true),
                    cell(new Phrase(line.itm8(), text), ALIGN[2], true),
                    cell(new Phrase(line.ean() == null ? "" : line.ean(), text), ALIGN[3], true),
                    cell(new Phrase(line.name(), text), ALIGN[4], false),
                    cell(new Phrase(quantity(line.quantity()), text), ALIGN[5], true),
                    cell(new Phrase(DATE.format(line.bestBefore()), strong), ALIGN[6], true),
                    cell(new Phrase(line.daysForStore() == null ? "" : String.valueOf(line.daysForStore()),
                            otherDlv ? strong : text), ALIGN[7], true),
                    cell(new Phrase(DATE.format(line.required()), text), ALIGN[8], true),
                    cell(new Phrase(line.missingDays() + " " + days(line.missingDays()), strong), ALIGN[9], true) };
            for (PdfPCell cell : cells) {
                if (background != null) cell.setBackgroundColor(background);
            }
            cells[6].setBackgroundColor(ALERT);
            if (otherDlv) cells[7].setBackgroundColor(OTHER_DLV);
            for (PdfPCell cell : cells) table.addCell(cell);
            index++;
        }
        return table;
    }

    private static final float WIDTHS_TOTAL = sum(WIDTHS);

    private static float sum(float[] values) {
        float total = 0;
        for (float value : values) total += value;
        return total;
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

    private static PdfPCell cell(Phrase phrase, int align, boolean noWrap) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(RULE);
        cell.setNoWrap(noWrap);
        cell.setPaddingTop(3.5f);
        cell.setPaddingBottom(4.5f);
        cell.setPaddingLeft(3);
        cell.setPaddingRight(3);
        return cell;
    }

    private static String days(long count) {
        return count == 1 ? "dzień" : "dni";
    }

    private static String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
