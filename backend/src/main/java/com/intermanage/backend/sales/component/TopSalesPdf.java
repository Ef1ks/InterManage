package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.TopSalesMetric;
import com.intermanage.backend.sales.dto.TopSalesReport;
import com.intermanage.backend.sales.dto.TopSalesReport.Group;
import com.intermanage.backend.sales.dto.TopSalesReport.OtherStore;
import com.intermanage.backend.sales.dto.TopSalesReport.Row;
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
 * Wydruk topów sprzedażowych: każda drukowana pozycja nomenklatury to osobna tabela z belką (kod, nazwa, sumy) i
 * nagłówkiem kolumn powtarzanym na kolejnych stronach. Przy porównaniu z więcej niż dwoma sklepami kartka jest
 * pozioma. Wartości jak na ekranie; ITM8 bez zer wiodących, jak w programie sklepu.
 */
@Component
public class TopSalesPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(228, 228, 231);
    private static final Color HEAD = new Color(244, 244, 245);

    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final float CELL_PADDING = 2.5f;
    /** Od tylu porównywanych sklepów kartka jest pozioma. */
    private static final int LANDSCAPE_FROM_OTHERS = 3;

    private final BaseFont regular;
    private final BaseFont bold;

    public TopSalesPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code groups}: pozycje do wydruku w kolejności raportu, {@code filters}: opis filtrów do nagłówka. */
    public byte[] pdf(TopSalesReport report, List<Group> groups, List<String> filters, String printedBy) {
        TopSalesReport.Store ranked = report.stores().getFirst();
        List<TopSalesReport.Store> others = report.stores().subList(1, report.stores().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rectangle page = others.size() >= LANDSCAPE_FROM_OTHERS ? PageSize.A4.rotate() : PageSize.A4;
        com.lowagie.text.Document document = new com.lowagie.text.Document(page, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Topy sprzedażowe · sklep " + ranked.number() + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Topy sprzedażowe");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(report, ranked, groups, filters));
            for (Group group : groups) {
                document.add(groupTable(report, group, others));
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku topów", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(TopSalesReport report, TopSalesReport.Store ranked, List<Group> groups,
            List<String> filters) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase("Topy sprzedażowe: " + metricLabel(report.metric()) + "\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + ranked.number() + " · " + ranked.name() + " · " + DATE.format(report.from())
                + " – " + DATE.format(report.to()) + " · " + report.limit() + " najlepszych w pozycji · pozycji: "
                + groups.size() + "\n", font(bold, 9, INK)));
        List<String> notes = new ArrayList<>(filters);
        if (ranked.promotionSkipped() > 0) {
            notes.add("pominięto " + ranked.promotionSkipped() + " art. z promocji z marżą do "
                    + report.maxPromotionMarginPercent() + "%");
        }
        if (ranked.noCostSkipped() > 0) {
            notes.add("pominięto " + ranked.noCostSkipped() + " art. z zerowym kosztem FIFO");
        }
        if (!notes.isEmpty()) {
            header.add(new Phrase(String.join(" · ", notes), font(regular, 7.5f, MUTED)));
        }
        return header;
    }

    private PdfPTable groupTable(TopSalesReport report, Group group, List<TopSalesReport.Store> others)
            throws DocumentException {
        List<Float> widths = new ArrayList<>(List.of(22f, 220f, 52f, 64f, 60f, 44f));
        others.forEach(store -> widths.add(56f));
        float[] columns = new float[widths.size()];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = widths.get(index);
        }
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        table.setHeaderRows(2);
        table.setSplitLate(false);

        String code = group.code();
        String title = code == null ? "Cały sklep"
                : (code.isEmpty() ? "Bez nomenklatury"
                        : spaced(code) + "  " + (group.name() != null ? nomenclatureName(group.name())
                                : "Brak nazwy w nomenklaturze"));
        Phrase band = new Phrase();
        band.add(new Phrase(title, font(bold, 9, INK)));
        band.add(new Phrase("   " + Math.min(report.limit(), group.articleCount()) + " z " + group.articleCount()
                + " art. · sprzedaż netto " + money(group.saleValueNet()) + " zł · marża "
                + money(group.marginValue()) + " zł · marża " + percent(group.marginPercent()),
                font(regular, 7.5f, MUTED)));
        PdfPCell bandCell = cell(band);
        bandCell.setColspan(columns.length);
        bandCell.setBackgroundColor(BAND);
        bandCell.setPadding(4);
        table.addCell(bandCell);

        TopSalesMetric metric = report.metric();
        head(table, "Nr", true, false);
        head(table, "Artykuł", false, false);
        head(table, "Ilość", true, metric == TopSalesMetric.QUANTITY);
        head(table, "Sprzedaż brutto", true, false);
        head(table, "Marża", true, metric == TopSalesMetric.MARGIN);
        head(table, "Marża %", true, metric == TopSalesMetric.MARGIN_PERCENT);
        others.forEach(store -> head(table, store.number(), true, false));

        for (Row row : group.rows()) {
            table.addCell(number(String.valueOf(row.rank()), false));
            Phrase article = new Phrase();
            article.add(new Phrase(row.name() != null ? row.name() : "Brak w kartotece", font(regular, 7.5f, INK)));
            List<String> details = new ArrayList<>();
            details.add("ITM8 " + row.itm8().replaceFirst("^0+", ""));
            if (row.ean() != null && !row.ean().isBlank()) {
                details.add("EAN " + row.ean());
            }
            if (row.brand() != null && !row.brand().isBlank()) {
                details.add(row.brand());
            }
            article.add(new Phrase("\n" + String.join(" · ", details), font(regular, 6.5f, MUTED)));
            table.addCell(cell(article));
            table.addCell(number(quantity(row.quantity()), metric == TopSalesMetric.QUANTITY));
            table.addCell(number(money(row.saleValueGross()), false));
            table.addCell(number(money(row.marginValue()), metric == TopSalesMetric.MARGIN));
            table.addCell(number(percent(row.marginPercent()), metric == TopSalesMetric.MARGIN_PERCENT));
            for (TopSalesReport.Store store : others) {
                OtherStore other = row.others().stream().filter(item -> item.storeId().equals(store.id()))
                        .findFirst().orElse(null);
                if (other == null) {
                    table.addCell(number("–", false));
                } else {
                    Phrase value = new Phrase();
                    value.add(new Phrase(metricValue(metric, other), font(regular, 7.5f, INK)));
                    value.add(new Phrase("\nmiejsce " + other.rank(), font(regular, 6.5f, MUTED)));
                    PdfPCell cell = cell(value);
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(cell);
                }
            }
        }
        return table;
    }

    private void head(PdfPTable table, String text, boolean right, boolean strong) {
        PdfPCell cell = cell(new Phrase(text, font(bold, 7, strong ? INK : MUTED)));
        cell.setBackgroundColor(HEAD);
        if (right) {
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        }
        table.addCell(cell);
    }

    private PdfPCell number(String value, boolean strong) {
        PdfPCell cell = cell(new Phrase(value, font(strong ? bold : regular, 7.5f, INK)));
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

    public static String metricLabel(TopSalesMetric metric) {
        return switch (metric) {
            case QUANTITY -> "ilość";
            case MARGIN -> "marża zł";
            case MARGIN_PERCENT -> "marża %";
        };
    }

    private static String metricValue(TopSalesMetric metric, OtherStore other) {
        return switch (metric) {
            case QUANTITY -> quantity(other.quantity());
            case MARGIN -> money(other.marginValue()) + " zł";
            case MARGIN_PERCENT -> percent(other.marginPercent());
        };
    }

    /** „01_DELIKATESY” → „DELIKATESY”, jak na ekranie. */
    public static String nomenclatureName(String name) {
        return name.replaceFirst("^\\d+_", "");
    }

    /** Kod nomenklatury w parach cyfr: „010101” → „01 01 01”. */
    public static String spaced(String code) {
        return code.replaceAll("(\\d{2})(?=\\d)", "$1 ");
    }

    private static String quantity(BigDecimal value) {
        return value != null
                ? new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(POLISH)).format(value)
                : "";
    }

    private static String money(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value) : "";
    }

    private static String percent(BigDecimal value) {
        return value != null ? new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value) + "%"
                : "–";
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
