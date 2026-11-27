package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.ArticleListReport;
import com.intermanage.backend.sales.dto.ArticleListReport.Group;
import com.intermanage.backend.sales.dto.ArticleListReport.Item;
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
 * Wydruk listy artykułów: wybrane kolumny w wybranej kolejności, przy grupowaniu belki rejonów, rodzin i podrodzin z
 * liczbą artykułów i wartością stanu. Wąska lista idzie pionowo, szeroka poziomo, a przy wielu kolumnach czcionka
 * maleje. Bez artykułów (same podsumowania) belki są jedynymi wierszami.
 */
@Component
public class ArticleListPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final Color[] GROUP_BANDS = {new Color(219, 234, 254), new Color(237, 233, 254),
        new Color(204, 251, 241)};

    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final float CELL_PADDING = 2.5f;
    /** Do tej sumy szerokości kolumn mieści się kartka pionowo. */
    private static final float PORTRAIT_WIDTH = 560;

    private final BaseFont regular;
    private final BaseFont bold;

    public ArticleListPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(ArticleListReport report, List<ArticleListColumn> columns, List<Item> items, int depth,
            boolean withArticles, List<String> filters, String printedBy) {
        float total = (float) columns.stream().mapToDouble(ArticleListColumn::pdfWidth).sum();
        boolean portrait = total <= PORTRAIT_WIDTH;
        float size = columns.size() > 16 ? 5.5f : columns.size() > 12 ? 6.5f : 7.5f;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(
                portrait ? PageSize.A4 : PageSize.A4.rotate(), MARGIN, MARGIN, MARGIN, MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Lista artykułów · sklep " + report.storeNumber() + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE)) + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Lista artykułów");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(report, filters));
            document.add(withArticles ? articles(report, columns, items, depth, size)
                    : summary(report, depth));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku listy artykułów", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(ArticleListReport report, List<String> filters) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(8);
        header.add(new Phrase("Lista artykułów\n", font(bold, 14, INK)));
        header.add(new Phrase("Sklep " + report.storeNumber() + " · " + report.storeName() + " · ",
                font(regular, 9, INK)));
        header.add(new Phrase("artykułów: " + report.itemCount() + " · wartość stanu: "
                + ArticleListColumn.money(report.stockValue()) + " zł zakupu, "
                + ArticleListColumn.money(report.stockSaleValue()) + " zł sprzedaży\n", font(bold, 9, INK)));
        header.add(new Phrase("Dane z raportu kartoteki z " + dateTime(report.stockReportGeneratedAt()) + ".",
                font(regular, 7.5f, MUTED)));
        if (!filters.isEmpty()) {
            header.add(new Phrase("\n" + String.join(" · ", filters), font(regular, 7.5f, MUTED)));
        }
        return header;
    }

    private PdfPTable articles(ArticleListReport report, List<ArticleListColumn> columns, List<Item> items, int depth,
            float size) throws DocumentException {
        float[] widths = new float[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            widths[index] = columns.get(index).pdfWidth();
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (ArticleListColumn column : columns) {
            PdfPCell cell = cell(new Phrase(column.label(), font(bold, size - 0.5f, MUTED)));
            cell.setBackgroundColor(BAND);
            if (column.numeric()) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
        Map<String, Group> groups = groupsByKey(report);
        String[] current = new String[depth];
        for (Item item : items) {
            for (int level = 1; level <= depth; level++) {
                String code = codeAt(item, level);
                String key = keyOf(code, level);
                if (!key.equals(current[level - 1])) {
                    current[level - 1] = key;
                    for (int below = level; below < depth; below++) {
                        current[below] = null;
                    }
                    Group group = groups.get(key);
                    if (group != null) {
                        table.addCell(groupCell(group, columns.size(), size));
                    }
                }
            }
            for (ArticleListColumn column : columns) {
                PdfPCell cell = cell(new Phrase(column.format(item), font(regular, size, INK)));
                if (column.numeric()) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    private PdfPTable summary(ArticleListReport report, int depth) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {300, 60, 90, 90});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        String[] headers = {"Nomenklatura", "Artykułów", "Wartość stanu (zakup)", "Wartość stanu (sprzedaż)"};
        for (int index = 0; index < headers.length; index++) {
            PdfPCell cell = cell(new Phrase(headers[index], font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            if (index > 0) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
        for (Group group : report.groups()) {
            if (group.level() > depth) {
                continue;
            }
            Font font = font(group.level() == 1 ? bold : regular, 8, INK);
            PdfPCell name = cell(new Phrase(groupTitle(group), font));
            name.setPaddingLeft(CELL_PADDING + (group.level() - 1) * 12);
            if (group.level() == 1) {
                name.setBackgroundColor(GROUP_BANDS[0]);
            }
            table.addCell(name);
            table.addCell(right(String.valueOf(group.count()), font, group.level() == 1));
            table.addCell(right(ArticleListColumn.money(group.stockValue()), font, group.level() == 1));
            table.addCell(right(ArticleListColumn.money(group.stockSaleValue()), font, group.level() == 1));
        }
        Font total = font(bold, 8, INK);
        table.addCell(cell(new Phrase("Razem", total)));
        table.addCell(right(String.valueOf(report.itemCount()), total, false));
        table.addCell(right(ArticleListColumn.money(report.stockValue()), total, false));
        table.addCell(right(ArticleListColumn.money(report.stockSaleValue()), total, false));
        return table;
    }

    private PdfPCell right(String text, Font font, boolean band) {
        PdfPCell cell = cell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (band) {
            cell.setBackgroundColor(GROUP_BANDS[0]);
        }
        return cell;
    }

    private PdfPCell groupCell(Group group, int span, float size) {
        String text = groupTitle(group) + "  ·  artykułów: " + group.count() + "  ·  stan: "
                + ArticleListColumn.money(group.stockValue()) + " zł";
        PdfPCell cell = cell(new Phrase(text, font(bold, size + (group.level() == 1 ? 0.5f : 0), INK)));
        cell.setColspan(span);
        cell.setBackgroundColor(GROUP_BANDS[Math.min(group.level(), GROUP_BANDS.length) - 1]);
        cell.setPaddingLeft(CELL_PADDING + (group.level() - 1) * 10);
        return cell;
    }

    public static String groupTitle(Group group) {
        if (group.code().isEmpty()) {
            return "Bez nomenklatury";
        }
        String code = group.code().replaceAll("(..)(?=.)", "$1 ");
        return group.name() == null ? code : code + " " + group.name();
    }

    /** Kod grupy artykułu na poziomie 1-3; pusty = bez nomenklatury. */
    public static String codeAt(Item item, int level) {
        String code = item.subfamilyCode();
        if (code == null || code.length() < 6) {
            return "";
        }
        return code.substring(0, level * 2);
    }

    public static String keyOf(String code, int level) {
        return level + ":" + code;
    }

    private static Map<String, Group> groupsByKey(ArticleListReport report) {
        Map<String, Group> groups = new java.util.HashMap<>();
        report.groups().forEach(group -> groups.put(keyOf(group.code(), group.level()), group));
        return groups;
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

    private static String dateTime(LocalDateTime value) {
        return value != null ? DATE_TIME.format(value) : "brak";
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
