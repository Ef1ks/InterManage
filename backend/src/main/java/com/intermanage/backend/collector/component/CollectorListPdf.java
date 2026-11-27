package com.intermanage.backend.collector.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
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

/** Wydruk listy z kolektora: A4 poziomo, kolumny jak na ekranie, na dole sumy i miejsce na podpis. */
@Component
public class CollectorListPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);
    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;

    private static final String[] HEADERS = {"Lp.", "ITM8", "EAN", "Nazwa", "Marka", "Zakup netto", "Wartość zakupu",
        "Sprzedaż szt.", "Wartość sprzedaży", "Marża %", "Ilość"};
    private static final float[] WIDTHS = {22, 48, 72, 196, 88, 54, 64, 56, 64, 44, 46};
    private static final int TEXT_COLUMNS = 5;

    private final BaseFont regular;
    private final BaseFont bold;

    public CollectorListPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(String storeNumber, CollectorListDetails details, String printedBy) {
        String lists = details.lists().stream()
                .map(list -> list.collectorName() + " " + DATE_TIME.format(list.receivedAt().atZone(StoreTime.ZONE)))
                .collect(Collectors.joining(", "));
        List<String> protocols = details.lists().stream().flatMap(list -> list.protocols().stream())
                .map(protocol -> protocol.kind().label()).distinct().toList();
        return pdf("Lista z kolektora", lists, protocols.isEmpty() ? null : "Protokół: " + String.join(", ", protocols),
                storeNumber, details, printedBy, SIGNATURES);
    }

    /** Podpisy pod listą, gdy wydruk nie poda własnych. */
    public static final List<String> SIGNATURES = List.of("Sporządził", "Sprawdził");

    /**
     * Ten sam układ z własnym tytułem (inwentaryzacja, zamówienie, zwrot do dostawcy) i własnymi miejscami na podpis
     * (każde z linią i opisem „data i podpis” pod spodem).
     */
    public byte[] pdf(String title, String subtitle, String note, String storeNumber, CollectorListDetails details,
            String printedBy, List<String> signatures) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), MARGIN, MARGIN, MARGIN, MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), title + " · sklep " + storeNumber
                    + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "")));
            document.addTitle(title);
            document.addCreator("Intermanage");
            document.open();
            document.add(header(title, subtitle, note, storeNumber, details));
            document.add(table(details));
            document.add(signatures(signatures));
            document.close();
        } catch (DocumentException exception) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku listy", exception);
        }
        return out.toByteArray();
    }

    /**
     * Miejsca na podpis obok siebie: wolne miejsce, linia i pod nią kto podpisuje. Tabela nie dzieli się między strony,
     * więc podpisy nie zostają same na kolejnej kartce bez kontekstu.
     */
    private PdfPTable signatures(List<String> labels) throws DocumentException {
        PdfPTable table = new PdfPTable(labels.size());
        table.setWidthPercentage(labels.size() == 1 ? 40 : Math.min(100, labels.size() * 33));
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(36);
        table.setKeepTogether(true);
        for (String label : labels) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingLeft(12);
            cell.setPaddingRight(12);
            Paragraph space = new Paragraph(" ", font(regular, 9, INK));
            space.setSpacingAfter(22);
            cell.addElement(space);
            PdfPTable line = new PdfPTable(1);
            line.setWidthPercentage(100);
            PdfPCell rule = new PdfPCell(new Phrase(label + " (data i podpis)", font(regular, 8, MUTED)));
            rule.setBorder(Rectangle.TOP);
            rule.setBorderColorTop(INK);
            rule.setBorderWidthTop(0.6f);
            rule.setHorizontalAlignment(Element.ALIGN_CENTER);
            rule.setPaddingTop(3);
            line.addCell(rule);
            cell.addElement(line);
            table.addCell(cell);
        }
        return table;
    }

    private Paragraph header(String title, String subtitle, String note, String storeNumber,
            CollectorListDetails details) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase(title + "\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + storeNumber + (subtitle == null || subtitle.isBlank() ? "" : " · " + subtitle)
                + " · ", font(regular, 9, INK)));
        header.add(new Phrase("artykułów: " + details.rows().size() + "\n", font(bold, 9, INK)));
        if (note != null && !note.isBlank()) {
            header.add(new Phrase(note, font(regular, 9, INK)));
        }
        return header;
    }

    private PdfPTable table(CollectorListDetails details) throws DocumentException {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitRows(false);
        for (int column = 0; column < HEADERS.length; column++) {
            PdfPCell cell = cell(HEADERS[column], font(bold, 7, MUTED), column >= TEXT_COLUMNS);
            cell.setBackgroundColor(BAND);
            table.addCell(cell);
        }
        Font text = font(regular, 8, INK);
        int number = 1;
        for (CollectorListDetails.Row row : details.rows()) {
            table.addCell(cell(String.valueOf(number++), text, false));
            table.addCell(cell(row.itm8().replaceFirst("^0+", ""), text, false));
            table.addCell(cell(row.ean(), text, false));
            table.addCell(cell(row.name(), text, false));
            table.addCell(cell(row.brand(), text, false));
            table.addCell(cell(money(row.purchasePrice()), text, true));
            table.addCell(cell(money(row.purchaseValue()), text, true));
            table.addCell(cell(money(row.salePrice()), text, true));
            table.addCell(cell(money(row.saleValue()), text, true));
            table.addCell(cell(row.marginPercent() == null ? "" : money(row.marginPercent()), text, true));
            table.addCell(cell(quantity(row.quantity()), font(bold, 8, INK), true));
        }
        Font total = font(bold, 8, INK);
        PdfPCell label = cell("Razem", total, false);
        label.setColspan(6);
        table.addCell(label);
        table.addCell(cell(money(details.totalPurchaseValue()), total, true));
        table.addCell(cell("", total, true));
        table.addCell(cell(money(details.totalSaleValue()), total, true));
        table.addCell(cell("", total, true));
        table.addCell(cell(quantity(details.totalQuantity()), total, true));
        return table;
    }

    private static PdfPCell cell(String value, Font font, boolean right) {
        PdfPCell cell = new PdfPCell(new Phrase(value != null ? value : "", font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(3);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (right) {
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        }
        return cell;
    }

    private static String quantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH))
                .format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
