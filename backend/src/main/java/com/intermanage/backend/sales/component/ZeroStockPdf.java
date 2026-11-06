package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfBarcodes;
import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.ZeroStockReport;
import com.intermanage.backend.sales.dto.ZeroStockReport.Group;
import com.intermanage.backend.sales.dto.ZeroStockReport.Item;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Wydruk raportu zerówek: A4 pionowo, artykuły po rodzinach, z EAN-em i ITM8 tekstem i kodami kreskowymi do skanowania
 * przy półce. Kolumna ITM8 jest bez zer wiodących, a kod kreskowy ma pełne 13 cyfr: tabela wprowadzania danych krótki
 * kod od 21-29 bierze za kod z etykiety wagi. Wiersze nie dzielą się między strony.
 */
@Component
public class ZeroStockPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final String[] HEADERS = {"ITM8", "EAN", "Nazwa", "Marka", "Stan", "Kod EAN", "Kod ITM8"};
    private static final float[] WIDTHS = {40, 78, 126, 54, 32, 110, 110};
    private static final float BARCODE_MAX_WIDTH = 102;
    private static final float BARCODE_MAX_HEIGHT = 34;

    private final BaseFont regular;
    private final BaseFont bold;

    public ZeroStockPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(ZeroStockReport report, String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Raport zerówek · sklep " + report.storeNumber() + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Raport zerówek");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(report));
            document.add(table(report, writer.getDirectContent()));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku raportu zerówek", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(ZeroStockReport report) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase("Raport zerówek\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + report.storeNumber() + " · " + report.storeName() + " · ",
                font(regular, 9, INK)));
        header.add(new Phrase("artykułów: " + report.itemCount() + "\n", font(bold, 9, INK)));
        header.add(new Phrase("Nomenklatura: " + report.selection().stream()
                .map(option -> option.code() + " " + option.name().replaceFirst("^\\d+_", ""))
                .collect(Collectors.joining(", ")) + "\n", font(regular, 7.5f, MUTED)));
        header.add(new Phrase("Artykuły aktywne w cadencier ze stanem 0. Stany z raportu z "
                + date(report.stockReportGeneratedAt()) + ", lista cadencier z "
                + date(report.cadencierGeneratedAt()) + ".", font(regular, 7.5f, MUTED)));
        return header;
    }

    private PdfPTable table(ZeroStockReport report, PdfContentByte canvas) throws DocumentException {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String title : HEADERS) {
            PdfPCell cell = cell(new Phrase(title, font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            table.addCell(cell);
        }
        for (Group group : report.groups()) {
            String title = group.name() != null ? group.code() + " " + group.name() : group.code();
            PdfPCell family = cell(new Phrase(title + " (" + group.items().size() + ")", font(bold, 8, INK)));
            family.setColspan(HEADERS.length);
            family.setBackgroundColor(BAND);
            table.addCell(family);
            for (Item item : group.items()) {
                addArticle(table, item, canvas);
            }
        }
        return table;
    }

    private void addArticle(PdfPTable table, Item item, PdfContentByte canvas) {
        table.addCell(text(withoutLeadingZeros(item.itm8())));
        table.addCell(text(item.ean()));
        table.addCell(text(item.name()));
        table.addCell(text(item.brand()));
        table.addCell(text(stock(item.stock())));
        table.addCell(barcode(item.ean(), canvas));
        table.addCell(barcode(item.itm8(), canvas));
    }

    private PdfPCell text(String value) {
        return cell(new Phrase(value != null ? value : "", font(regular, 7.5f, INK)));
    }

    private PdfPCell barcode(String code, PdfContentByte canvas) {
        if (code == null || code.isBlank()) {
            return text(null);
        }
        Image image = PdfBarcodes.image(canvas, code, BARCODE_MAX_WIDTH, BARCODE_MAX_HEIGHT);
        PdfPCell cell = new PdfPCell(image, false);
        style(cell);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        style(cell);
        return cell;
    }

    private static void style(PdfPCell cell) {
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(3);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
    }

    private static String withoutLeadingZeros(String itm8) {
        return itm8.replaceFirst("^0+", "");
    }

    private static String stock(BigDecimal stock) {
        return stock != null ? stock.stripTrailingZeros().toPlainString() : "0";
    }

    private static String date(LocalDateTime date) {
        return date != null ? DATE_TIME.format(date) : "brak";
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
