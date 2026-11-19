package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.Percent;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.PeriodSalesDepth;
import com.intermanage.backend.sales.dto.PeriodSalesReport;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Article;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Figures;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Node;
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
 * Wydruk sprzedaży za okres: A4 pionowo, jedna tabela z nagłówkiem kolumn na każdej stronie, wiersze nomenklatury do
 * wybranego poziomu (wcięte, rejon na szarym tle), przy poziomie artykułów artykuły pod swoją podrodziną, na końcu
 * „Razem”. Udział = sprzedaż brutto pozycji w sprzedaży brutto wydruku.
 */
@Component
public class PeriodSalesPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color REGION = new Color(228, 228, 231);
    private static final Color HEAD = new Color(244, 244, 245);

    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final float INDENT = 10;
    private static final String[] HEADERS = {"Pozycja", "Ilość", "Zakup FIFO", "Sprzedaż netto", "Sprzedaż brutto",
        "Marża", "Marża %", "Udział"};
    private static final float[] WIDTHS = {206, 48, 58, 60, 62, 56, 38, 36};

    private final BaseFont regular;
    private final BaseFont bold;

    public PeriodSalesPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code articles}: artykuły według podrodziny (kod pusty = bez nomenklatury), tylko przy poziomie artykułów. */
    public byte[] pdf(String storeNumber, String storeName, PeriodSalesReport report, PeriodSalesDepth depth,
            Map<String, List<Article>> articles, List<String> filters, String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Sprzedaż za okres · sklep " + storeNumber + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Sprzedaż za okres");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(storeNumber, storeName, report, depth, filters));
            document.add(table(report, depth, articles));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku sprzedaży", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(String storeNumber, String storeName, PeriodSalesReport report, PeriodSalesDepth depth,
            List<String> filters) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase("Sprzedaż za okres\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + storeNumber + " · " + storeName + " · " + DATE.format(report.from()) + " – "
                + DATE.format(report.to()) + " · artykułów: " + report.articleCount() + "\n", font(bold, 9, INK)));
        String notes = "Szczegółowość: " + depthLabel(depth) + ". Marża od sprzedaży brutto, zakup w cenach FIFO."
                + (filters.isEmpty() ? " Cała sprzedaż sklepu." : " Tylko sprzedaż spełniająca filtry: "
                        + String.join(" · ", filters) + ".");
        header.add(new Phrase(notes, font(regular, 7.5f, MUTED)));
        return header;
    }

    private PdfPTable table(PeriodSalesReport report, PeriodSalesDepth depth, Map<String, List<Article>> articles)
            throws DocumentException {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (int column = 0; column < HEADERS.length; column++) {
            PdfPCell cell = cell(new Phrase(HEADERS[column], font(bold, 7, MUTED)), HEAD);
            if (column > 0) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
        BigDecimal total = report.total().saleValueGross();
        for (Node region : report.regions()) {
            boolean unassigned = region.code().isEmpty();
            row(table, nodeLabel(region), region.figures(), total, 0, bold, 8, REGION);
            if (unassigned) {
                if (depth != PeriodSalesDepth.REGION) {
                    articles(table, articles.get(""), total, 1);
                }
                continue;
            }
            if (depth == PeriodSalesDepth.REGION) {
                continue;
            }
            for (Node family : region.children()) {
                row(table, nodeLabel(family), family.figures(), total, 1, bold, 7.5f, null);
                if (depth == PeriodSalesDepth.FAMILY) {
                    continue;
                }
                for (Node subfamily : family.children()) {
                    row(table, nodeLabel(subfamily), subfamily.figures(), total, 2,
                            depth == PeriodSalesDepth.ARTICLE ? bold : regular, 7.5f, null);
                    if (depth == PeriodSalesDepth.ARTICLE) {
                        articles(table, articles.get(subfamily.code()), total, 3);
                    }
                }
            }
        }
        row(table, new Phrase("Razem", font(bold, 8, INK)), report.total(), total, 0, bold, 8, HEAD);
        return table;
    }

    private void articles(PdfPTable table, List<Article> articles, BigDecimal total, int level) {
        if (articles == null) {
            return;
        }
        for (Article article : articles) {
            Phrase label = new Phrase();
            label.add(new Phrase(article.name() != null ? article.name() : "Brak w kartotece",
                    font(regular, 7, INK)));
            String details = "ITM8 " + article.itm8().replaceFirst("^0+", "")
                    + (article.ean() != null && !article.ean().isBlank() ? " · EAN " + article.ean() : "")
                    + (article.brand() != null && !article.brand().isBlank() ? " · " + article.brand() : "");
            label.add(new Phrase("\n" + details, font(regular, 6, MUTED)));
            row(table, label, article.figures(), total, level, regular, 7, null);
        }
    }

    private void row(PdfPTable table, Phrase label, Figures figures, BigDecimal total, int level, BaseFont base,
            float size, Color background) {
        PdfPCell name = cell(label, background);
        name.setPaddingLeft(3 + level * INDENT);
        table.addCell(name);
        String[] values = {quantity(figures.quantity()), money(figures.costValue()), money(figures.saleValueNet()),
            money(figures.saleValueGross()), money(figures.marginValue()), percent(figures.marginPercent()),
            percent(Percent.of(figures.saleValueGross(), total))};
        for (String value : values) {
            PdfPCell cell = cell(new Phrase(value, font(base, size, INK)), background);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    private Phrase nodeLabel(Node node) {
        float size = "REGION".equals(node.level()) ? 8 : 7.5f;
        BaseFont base = "SUBFAMILY".equals(node.level()) ? regular : bold;
        if (node.code().isEmpty()) {
            return new Phrase("Bez nomenklatury", font(bold, size, INK));
        }
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(TopSalesPdf.spaced(node.code()) + "  ", font(regular, size - 1, MUTED)));
        phrase.add(new Phrase(node.name() != null ? TopSalesPdf.nomenclatureName(node.name())
                : "Brak nazwy w nomenklaturze", font(base, size, INK)));
        return phrase;
    }

    private static PdfPCell cell(Phrase phrase, Color background) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(2.5f);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        return cell;
    }

    private static String depthLabel(PeriodSalesDepth depth) {
        return switch (depth) {
            case REGION -> "rejony";
            case FAMILY -> "rejony i rodziny";
            case SUBFAMILY -> "do podrodzin";
            case ARTICLE -> "do artykułów";
        };
    }

    private static String quantity(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(POLISH)).format(value)
                : "";
    }

    private static String money(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value)
                : "";
    }

    private static String percent(BigDecimal value) {
        return value != null ? new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value) + "%"
                : "–";
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
