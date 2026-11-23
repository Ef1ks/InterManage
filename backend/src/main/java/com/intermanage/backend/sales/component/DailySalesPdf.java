package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.Percent;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.DailySalesReport;
import com.intermanage.backend.sales.dto.DailySalesReport.Figures;
import com.intermanage.backend.sales.dto.DailySalesReport.Line;
import com.intermanage.backend.sales.dto.DailySalesRequest.Layout;
import com.intermanage.backend.sales.dto.DailySalesRequest.Level;
import com.intermanage.backend.sales.dto.DailySalesRequest.Measure;
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
 * Wydruk sprzedaży dziennej: A4 poziomo, jedna wybrana miara w komórkach (kwoty w pełnych złotych). Układ „dni w
 * wierszach” mieści miesiąc rejonów na jednej kartce; „dni w kolumnach” to tabela jak na ekranie. Czcionka zmniejsza
 * się do szerokości i wysokości kartki (do {@link #MIN_FONT}); gdy kolumn jest za dużo nawet przy niej, tabela dzieli
 * się na kilka części z powtórzoną kolumną opisu.
 */
@Component
public class DailySalesPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);
    private static final String[] WEEKDAYS = {"pn", "wt", "śr", "cz", "pt", "sb", "nd"};

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color REGION = new Color(228, 228, 231);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final Color SUNDAY = new Color(254, 226, 226);

    private static final float MARGIN = 20;
    private static final float MARGIN_BOTTOM = 36;
    private static final float HEADER_HEIGHT = 46;
    private static final float MAX_FONT = 8;
    private static final float MIN_FONT = 4.5f;
    /** Kolumny powtarzane w każdej części tabeli zaraz za opisem: „Razem”. */
    private static final int PINNED = 1;
    private static final float CELL_PADDING = 1.5f;

    private final BaseFont regular;
    private final BaseFont bold;

    public DailySalesPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(String storeNumber, String storeName, DailySalesReport report, Level level, Measure measure,
            Layout layout, boolean byCategory, String printedBy) {
        Matrix matrix = layout == Layout.DAYS_AS_ROWS ? daysAsRows(report, level, measure)
                : daysAsColumns(report, level, measure);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rectangle page = PageSize.A4.rotate();
        com.lowagie.text.Document document = new com.lowagie.text.Document(page, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Sprzedaż dzienna · sklep " + storeNumber + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Sprzedaż dzienna");
            document.addCreator("Intermanage");
            document.open();
            document.add(header(storeNumber, storeName, report, level, measure, byCategory));
            float width = page.getWidth() - 2 * MARGIN;
            float height = page.getHeight() - MARGIN - MARGIN_BOTTOM - HEADER_HEIGHT;
            for (PdfPTable table : tables(matrix, width, height)) {
                document.add(table);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku sprzedaży dziennej", e);
        }
        return out.toByteArray();
    }

    private Paragraph header(String storeNumber, String storeName, DailySalesReport report, Level level,
            Measure measure, boolean byCategory) {
        Paragraph header = new Paragraph();
        header.setSpacingAfter(6);
        header.add(new Phrase("Sprzedaż dzienna · " + measureLabel(measure) + "\n", font(bold, 13, INK)));
        header.add(new Phrase("Sklep " + storeNumber + " · " + storeName + " · " + DATE.format(report.from())
                + " – " + DATE.format(report.to()) + "\n", font(bold, 8.5f, INK)));
        String rows = byCategory
                ? (level == Level.FAMILY ? "Kategorie sklepu i ich rejony" : "Kategorie sklepu")
                        + " (rejon w kilku kategoriach liczy się w każdej)."
                : (level == Level.FAMILY ? "Rejony i rodziny" : "Rejony") + " nomenklatury.";
        header.add(new Phrase(rows
                + (measure == Measure.QUANTITY || measure == Measure.MARGIN_PERCENT ? ""
                        : " Kwoty w pełnych złotych.")
                + " Marża od sprzedaży brutto. Bez opakowań zwrotnych i materiałów promocyjnych (rejony 90, 91).",
                font(regular, 7, MUTED)));
        return header;
    }

    /** Wiersze to nomenklatura, kolumny to „Razem” (zaraz przy opisie) i dni. */
    private Matrix daysAsColumns(DailySalesReport report, Level level, Measure measure) {
        List<Header> columns = new ArrayList<>();
        columns.add(new Header("Razem", "", false, true));
        report.days().forEach(day -> columns.add(new Header(weekday(day), DAY.format(day), sunday(day), false)));
        List<Row> rows = new ArrayList<>();
        for (Line line : lines(report, level)) {
            List<String> values = new ArrayList<>();
            values.add(value(line.sum(), measure));
            line.days().forEach(day -> values.add(value(day, measure)));
            boolean top = top(report, line);
            rows.add(new Row(lineLabel(line), top ? 0 : 1, top && level == Level.FAMILY, values));
        }
        List<String> total = new ArrayList<>();
        total.add(value(report.total().sum(), measure));
        report.total().days().forEach(day -> total.add(value(day, measure)));
        rows.add(new Row("Razem sklep", 0, true, total));
        return new Matrix("Nomenklatura", columns, rows);
    }

    /** Wiersze to dni i „Razem”, kolumny to „Razem sklep” (zaraz przy dniu) i nomenklatura. */
    private Matrix daysAsRows(DailySalesReport report, Level level, Measure measure) {
        List<Line> lines = lines(report, level);
        List<Header> columns = new ArrayList<>();
        columns.add(new Header("Razem", "sklep", false, true));
        for (Line line : lines) {
            boolean category = "CATEGORY".equals(line.level());
            String name = line.code().isEmpty() ? "Bez nomenklatury"
                    : line.name() != null ? TopSalesPdf.nomenclatureName(line.name()) : "";
            // Nazwa kategorii łamie się w dolnym wierszu nagłówka, więc nie poszerza kolumny.
            String code = category ? "" : line.code().isEmpty() ? "–" : TopSalesPdf.spaced(line.code());
            columns.add(new Header(code, name, false, top(report, line) && level == Level.FAMILY));
        }
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < report.days().size(); index++) {
            LocalDate day = report.days().get(index);
            List<String> values = new ArrayList<>();
            values.add(value(report.total().days().get(index), measure));
            for (Line line : lines) {
                values.add(value(line.days().get(index), measure));
            }
            rows.add(new Row(weekday(day) + " " + DAY.format(day), 0, false, values, sunday(day)));
        }
        List<String> total = new ArrayList<>();
        total.add(value(report.total().sum(), measure));
        lines.forEach(line -> total.add(value(line.sum(), measure)));
        rows.add(new Row("Razem", 0, true, total));
        return new Matrix("Dzień", columns, rows);
    }

    private static List<Line> lines(DailySalesReport report, Level level) {
        List<Line> lines = new ArrayList<>();
        for (Line region : report.regions()) {
            lines.add(region);
            if (level == Level.FAMILY) {
                lines.addAll(region.children());
            }
        }
        return lines;
    }

    /**
     * Tabele wydruku: szerokość każdej kolumny z najdłuższego napisu w niej, czcionka od {@link #MAX_FONT} w dół, aż
     * kolumny zmieszczą się w szerokości, a wiersze w wysokości kartki; przy zbyt wielu kolumnach kolejne części
     * tabeli z powtórzoną kolumną opisu i „Razem” (pierwsza kolumna liczb, {@link #PINNED}).
     */
    private List<PdfPTable> tables(Matrix matrix, float width, float height) throws DocumentException {
        int columns = matrix.columns().size();
        // Szerokości przy czcionce 1 pt; skalują się liniowo z czcionką.
        float label = 0;
        for (Row row : matrix.rows()) {
            label = Math.max(label, (row.strong() ? bold : regular).getWidthPoint(row.label(), 1) + row.indent());
        }
        label = Math.max(label, bold.getWidthPoint(matrix.corner(), 1));
        float[] unit = new float[columns];
        for (int index = 0; index < columns; index++) {
            Header column = matrix.columns().get(index);
            float widest = bold.getWidthPoint(column.top(), 1);
            for (Row row : matrix.rows()) {
                BaseFont base = row.strong() || column.total() ? bold : regular;
                widest = Math.max(widest, base.getWidthPoint(row.values().get(index), 1));
            }
            unit[index] = widest;
        }
        float size = MAX_FONT;
        while (size > MIN_FONT && (span(label, unit, 0, columns, size) > width
                || rowHeight(size) * (matrix.rows().size() + 2) > height)) {
            size -= 0.25f;
        }
        List<PdfPTable> tables = new ArrayList<>();
        float pinned = span(label, unit, 0, PINNED, size) - label * size - 2 * CELL_PADDING;
        int start = PINNED;
        while (start < columns) {
            int end = start + 1;
            while (end < columns && span(label, unit, start, end + 1, size) + pinned <= width) {
                end++;
            }
            List<Integer> chosen = new ArrayList<>();
            for (int index = 0; index < PINNED; index++) {
                chosen.add(index);
            }
            for (int index = start; index < end; index++) {
                chosen.add(index);
            }
            tables.add(table(matrix, chosen, size, width, label, unit));
            start = end;
        }
        return tables;
    }

    /** Szerokość tabeli z kolumnami {@code from}..{@code to} przy czcionce {@code size}. */
    private static float span(float label, float[] unit, int from, int to, float size) {
        float total = label * size + 2 * CELL_PADDING;
        for (int index = from; index < to; index++) {
            total += unit[index] * size + 2 * CELL_PADDING;
        }
        return total;
    }

    private static float rowHeight(float size) {
        return size * 1.25f + 2 * CELL_PADDING + 0.5f;
    }

    private PdfPTable table(Matrix matrix, List<Integer> chosen, float size, float width, float label, float[] unit)
            throws DocumentException {
        int count = chosen.size();
        float[] widths = new float[count + 1];
        widths[0] = label * size + 2 * CELL_PADDING;
        float needed = widths[0];
        for (int index = 0; index < count; index++) {
            widths[index + 1] = unit[chosen.get(index)] * size + 2 * CELL_PADDING;
            needed += widths[index + 1];
        }
        // Zapas szerokości idzie po równo do kolumn liczb, opis zostaje wąski.
        float extra = Math.max(0, width - needed) / count;
        for (int index = 1; index <= count; index++) {
            widths[index] += extra;
        }
        PdfPTable table = new PdfPTable(widths);
        table.setTotalWidth(width);
        table.setLockedWidth(true);
        table.setHeaderRows(1);
        table.setSpacingAfter(8);

        table.addCell(cell(new Phrase(matrix.corner(), font(bold, size - 0.5f, MUTED)), HEAD, Element.ALIGN_LEFT));
        for (int index : chosen) {
            Header column = matrix.columns().get(index);
            Phrase phrase = new Phrase();
            phrase.add(new Phrase(column.top(), font(bold, size - 0.5f, column.total() ? INK : MUTED)));
            if (!column.bottom().isEmpty()) {
                phrase.add(new Phrase("\n" + column.bottom(), font(regular, size - 1.5f, MUTED)));
            }
            PdfPCell cell = cell(phrase, column.sunday() ? SUNDAY : HEAD, Element.ALIGN_RIGHT);
            cell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            table.addCell(cell);
        }

        for (Row row : matrix.rows()) {
            Color background = row.strong() ? (row.label().startsWith("Razem") ? HEAD : REGION)
                    : row.sunday() ? SUNDAY : null;
            BaseFont base = row.strong() ? bold : regular;
            PdfPCell name = cell(new Phrase(row.label(), font(base, size, INK)), background, Element.ALIGN_LEFT);
            name.setPaddingLeft(CELL_PADDING + row.indent() * size);
            name.setNoWrap(true);
            table.addCell(name);
            for (int index : chosen) {
                Header column = matrix.columns().get(index);
                Color tint = background != null ? background
                        : index < PINNED ? HEAD : column.sunday() ? SUNDAY : null;
                PdfPCell cell = cell(new Phrase(row.values().get(index),
                        font(column.total() ? bold : base, size, INK)), tint, Element.ALIGN_RIGHT);
                cell.setNoWrap(true);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static PdfPCell cell(Phrase phrase, Color background, int alignment) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setPadding(CELL_PADDING);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(alignment);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        return cell;
    }

    /** Pozycja pierwszego poziomu: kategoria albo, bez kategorii, rejon. */
    private static boolean top(DailySalesReport report, Line line) {
        return report.regions().contains(line);
    }

    private static String lineLabel(Line line) {
        if ("CATEGORY".equals(line.level())) {
            String name = line.name() != null ? line.name() : "";
            return name.length() > 34 ? name.substring(0, 33) + "…" : name;
        }
        if (line.code().isEmpty()) {
            return "Bez nomenklatury";
        }
        String name = line.name() != null ? TopSalesPdf.nomenclatureName(line.name()) : "";
        String label = TopSalesPdf.spaced(line.code()) + " " + name;
        return label.length() > 34 ? label.substring(0, 33) + "…" : label;
    }

    private static String value(Figures figures, Measure measure) {
        if (figures == null) {
            return "";
        }
        return switch (measure) {
            case GROSS -> money(figures.saleValueGross());
            case NET -> money(figures.saleValueNet());
            case MARGIN -> money(figures.marginValue());
            case QUANTITY -> money(figures.quantity());
            case MARGIN_PERCENT -> {
                BigDecimal percent = Percent.of(figures.marginValue(), figures.saleValueGross());
                yield percent == null ? "–"
                        : new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(POLISH)).format(percent);
            }
        };
    }

    private static String money(BigDecimal value) {
        return value == null ? ""
                : new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(POLISH))
                        .format(value.setScale(0, RoundingMode.HALF_UP)).replace(' ', ' ').replace(' ', ' ');
    }

    static String measureLabel(Measure measure) {
        return switch (measure) {
            case GROSS -> "sprzedaż brutto";
            case NET -> "sprzedaż netto";
            case MARGIN -> "marża zł";
            case MARGIN_PERCENT -> "marża %";
            case QUANTITY -> "ilość";
        };
    }

    private static String weekday(LocalDate day) {
        return WEEKDAYS[day.getDayOfWeek().getValue() - 1];
    }

    private static boolean sunday(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private record Header(String top, String bottom, boolean sunday, boolean total) {
    }

    private record Row(String label, int indent, boolean strong, List<String> values, boolean sunday) {
        Row(String label, int indent, boolean strong, List<String> values) {
            this(label, indent, strong, values, false);
        }
    }

    private record Matrix(String corner, List<Header> columns, List<Row> rows) {
    }
}
