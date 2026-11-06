package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.sales.dto.SlowMovingReport;
import com.intermanage.backend.sales.dto.SlowMovingReport.Delivery;
import com.intermanage.backend.sales.dto.SlowMovingReport.DeliveryItem;
import com.intermanage.backend.sales.dto.SlowMovingReport.Item;
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
 * Wydruk niesprzedających się artykułów: A4 poziomo, ta sama lista i kolejność co na ekranie, a z wybranym okresem
 * dostaw przyjęcia z ich artykułami. ITM8 jest bez zer wiodących, jak w programie sklepu.
 */
@Component
public class SlowMovingPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final float CELL_PADDING = 2.5f;

    private static final String[] ARTICLE_HEADERS = {"ITM8", "EAN", "Nazwa", "Marka", "Ostatni dostawca",
        "Ostatnia dostawa", "Stan", "Wartość stanu", "Sprzedano", "Rotacja / tydz."};
    private static final float[] ARTICLE_WIDTHS = {44, 66, 170, 70, 110, 54, 40, 56, 44, 44};
    private static final int ARTICLE_TEXT_COLUMNS = 6;

    private static final String[] DELIVERY_HEADERS = {"ITM8", "EAN", "Nazwa", "Marka", "Dostarczono", "Wartość dostawy",
        "Stan", "Sprzedano", "Rotacja / tydz."};
    private static final float[] DELIVERY_WIDTHS = {44, 66, 210, 80, 54, 60, 40, 44, 44};
    private static final int DELIVERY_TEXT_COLUMNS = 4;

    private final BaseFont regular;
    private final BaseFont bold;

    public SlowMovingPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(SlowMovingReport report, List<String> filters, String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4.rotate(), MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Niesprzedające się artykuły · sklep " + report.storeNumber() + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Niesprzedające się artykuły");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(report, filters));
            document.add(report.deliveries().isEmpty() ? articlesTable(report) : deliveriesTable(report));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku raportu", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(SlowMovingReport report, List<String> filters) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase("Niesprzedające się artykuły\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + report.storeNumber() + " · " + report.storeName() + " · ",
                font(regular, 9, INK)));
        header.add(new Phrase("artykułów: " + report.itemCount() + " · bez sprzedaży: " + report.unsoldCount()
                + " · wartość stanu: " + money(report.stockValue()) + " zł\n", font(bold, 9, INK)));
        header.add(new Phrase("Na stanie, rotacja tygodniowa najwyżej " + quantity(report.maxRotation()) + " w okresie "
                + DATE.format(report.from()) + " – " + DATE.format(report.to()) + ". Stany i ceny zakupu z raportu z "
                + dateTime(report.stockReportGeneratedAt()) + ".", font(regular, 7.5f, MUTED)));
        if (!filters.isEmpty()) {
            header.add(new Phrase("\n" + String.join(" · ", filters), font(regular, 7.5f, MUTED)));
        }
        return header;
    }

    private PdfPTable articlesTable(SlowMovingReport report) throws DocumentException {
        PdfPTable table = table(ARTICLE_WIDTHS, ARTICLE_HEADERS, ARTICLE_TEXT_COLUMNS);
        for (Item item : report.items()) {
            table.addCell(text(itm8(item.itm8())));
            table.addCell(text(item.ean()));
            table.addCell(text(item.name()));
            table.addCell(text(item.brand()));
            table.addCell(text(item.supplierName()));
            table.addCell(text(date(item.lastDeliveryDate())));
            table.addCell(number(quantity(item.stock())));
            table.addCell(number(money(item.stockValue())));
            table.addCell(number(quantity(item.soldQuantity())));
            table.addCell(number(rotation(item.weeklyRotation())));
        }
        return table;
    }

    private PdfPTable deliveriesTable(SlowMovingReport report) throws DocumentException {
        Map<String, Item> items = report.items().stream().collect(Collectors.toMap(Item::itm8, Function.identity()));
        PdfPTable table = table(DELIVERY_WIDTHS, DELIVERY_HEADERS, DELIVERY_TEXT_COLUMNS);
        for (Delivery delivery : report.deliveries()) {
            PdfPCell title = cell(new Phrase(deliveryTitle(delivery), font(bold, 8, INK)));
            title.setColspan(DELIVERY_HEADERS.length);
            title.setBackgroundColor(BAND);
            table.addCell(title);
            for (DeliveryItem line : delivery.items()) {
                Item item = items.get(line.itm8());
                table.addCell(text(itm8(item.itm8())));
                table.addCell(text(item.ean()));
                table.addCell(text(item.name()));
                table.addCell(text(item.brand()));
                table.addCell(number(quantity(line.quantity())));
                table.addCell(number(money(line.purchaseValue())));
                table.addCell(number(quantity(item.stock())));
                table.addCell(number(quantity(item.soldQuantity())));
                table.addCell(number(rotation(item.weeklyRotation())));
            }
        }
        return table;
    }

    private static String deliveryTitle(Delivery delivery) {
        return DATE.format(delivery.documentDate()) + " · bon " + Document.bonNumber(delivery.documentNumber()) + " · "
                + (delivery.supplierName() != null ? delivery.supplierName() : "brak dostawcy") + " · artykułów: "
                + delivery.items().size() + " · " + money(delivery.purchaseValue()) + " zł";
    }

    private PdfPTable table(float[] widths, String[] headers, int textColumns) throws DocumentException {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (int column = 0; column < headers.length; column++) {
            PdfPCell cell = cell(new Phrase(headers[column], font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            if (column >= textColumns) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
        return table;
    }

    private PdfPCell text(String value) {
        return cell(new Phrase(value != null ? value : "", font(regular, 7.5f, INK)));
    }

    private PdfPCell number(String value) {
        PdfPCell cell = text(value);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(CELL_PADDING);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static String itm8(String value) {
        return value.replaceFirst("^0+", "");
    }

    private static String quantity(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString().replace('.', ',') : "";
    }

    private static String rotation(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String money(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value) : "";
    }

    private static String date(LocalDate value) {
        return value != null ? DATE.format(value) : "brak";
    }

    private static String dateTime(LocalDateTime value) {
        return value != null ? DATE_TIME.format(value) : "brak";
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
