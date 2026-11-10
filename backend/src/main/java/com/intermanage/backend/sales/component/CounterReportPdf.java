package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.CounterReport;
import com.intermanage.backend.sales.dto.CounterReport.Basis;
import com.intermanage.backend.sales.dto.CounterReport.Figures;
import com.intermanage.backend.sales.dto.CounterReport.Metric;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Wydruk raportu lady (A4 poziomo): kafelek na sklep z sumą wybranych rejonów, potem tabela miar ze sklepami pod każdą
 * miarą, jak w dawnym arkuszu „36 szczegółowo”. Przy wielu sklepach i miarach kolumny nie zmieściłyby się w
 * szerokości kartki, więc miary dzielą się na kilka tabel po najwyżej {@value #MAX_COLUMNS} kolumn liczb, każda z
 * pełną nomenklaturą. Nagłówki tabel powtarzają się na kolejnych stronach.
 */
@Component
public class CounterReportPdf {

    /** Najwięcej kolumn liczb w jednej tabeli: przy szerokości A4 poziomo to ok. 36 pkt na liczbę. */
    static final int MAX_COLUMNS = 18;

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final Color REGION_BAND = new Color(212, 212, 216);
    private static final Color TOTAL_BAND = new Color(39, 39, 42);
    private static final Color BAD = new Color(153, 27, 27);
    private static final Color BAD_BAND = new Color(254, 202, 202);
    private static final Color GOOD = new Color(6, 95, 70);
    private static final Color GOOD_BAND = new Color(167, 243, 208);

    private static final Rectangle PAGE = PageSize.A4.rotate();
    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 36;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float NAME_WIDTH = 150;

    private final BaseFont regular;
    private final BaseFont bold;

    public CounterReportPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(CounterReport report, List<Metric> metrics, Basis basis, boolean withArticles,
            String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PAGE, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Raport lady · sklep " + report.stores().getFirst().number() + " · "
                    + period(report) + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Raport lady " + period(report));
            document.addCreator("Intermanage");
            document.open();
            document.add(locked(header(report, basis)));
            String warning = warning(report);
            if (warning != null) {
                document.add(locked(note(warning)));
            }
            List<List<Metric>> parts = split(metrics, report.stores().size());
            for (int part = 0; part < parts.size(); part++) {
                if (parts.size() > 1) {
                    document.add(locked(caption(part + 1, parts.size(), parts.get(part), basis)));
                }
                document.add(locked(table(report, parts.get(part), basis, withArticles)));
            }
            document.add(locked(legend(report.stores().size() > 1)));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku raportu lady", e);
        }
        return out.toByteArray();
    }

    /**
     * Miary w tabelach po najwyżej {@value #MAX_COLUMNS} kolumn liczb: tyle tabel, ile trzeba, z miarami rozłożonymi
     * po równo (5 miar w 6 sklepach: 3 + 2, a nie 3 + 1 + 1).
     */
    public static List<List<Metric>> split(List<Metric> metrics, int stores) {
        int tables = Math.max(1, (int) Math.ceil(metrics.size() * stores / (double) MAX_COLUMNS));
        int perTable = (int) Math.ceil(metrics.size() / (double) tables);
        List<List<Metric>> result = new ArrayList<>();
        for (int start = 0; start < metrics.size(); start += perTable) {
            result.add(metrics.subList(start, Math.min(metrics.size(), start + perTable)));
        }
        return result;
    }

    private static PdfPTable locked(PdfPTable table) {
        table.setTotalWidth(WIDTH);
        table.setLockedWidth(true);
        return table;
    }

    private PdfPTable header(CounterReport report, Basis basis) {
        int count = report.stores().size();
        int columns = Math.max(count, 3);
        PdfPTable header = new PdfPTable(columns);
        header.setSpacingAfter(8);

        Phrase title = new Phrase();
        title.add(new Phrase("Raport lady\n", font(bold, 14, INK)));
        long days = ChronoUnit.DAYS.between(report.from(), report.to()) + 1;
        title.add(new Phrase("Rejony " + String.join(", ", report.regionCodes()) + " · ",
                font(regular, 9, INK)));
        title.add(new Phrase(period(report) + " (" + days + (days == 1 ? " dzień" : " dni") + ")",
                font(bold, 9, INK)));
        PdfPCell titleCell = new PdfPCell(title);
        titleCell.setColspan(columns);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setLeading(0, 1.3f);
        titleCell.setPadding(0);
        titleCell.setPaddingBottom(6);
        header.addCell(titleCell);

        for (int i = 0; i < count; i++) {
            CounterReport.Store store = report.stores().get(i);
            Figures total = report.total().get(i);
            String sale = total == null ? "–" : money(basis == Basis.NET ? total.saleValueNet()
                    : total.saleValueGross()) + " zł";
            String detail = total == null ? "brak sprzedaży"
                    : "marża " + percent(total.marginPercent()) + " · " + money(total.marginValue()) + " zł";
            header.addCell(tile(store.number() + " · " + store.name(), sale, detail, i == 0));
        }
        for (int i = count; i < columns; i++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            header.addCell(empty);
        }
        return header;
    }

    private PdfPCell tile(String label, String value, String detail, boolean strong) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label + "\n", font(bold, 6.5f, strong ? Color.WHITE : MUTED)));
        phrase.add(new Phrase(value + "\n", font(bold, 12, strong ? Color.WHITE : INK)));
        phrase.add(new Phrase(detail, font(regular, 7, strong ? new Color(212, 212, 216) : MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderWidthLeft(4);
        cell.setBorderColorLeft(Color.WHITE);
        cell.setBackgroundColor(strong ? TOTAL_BAND : BAND);
        cell.setPadding(6);
        cell.setLeading(0, 1.25f);
        return cell;
    }

    private PdfPTable note(String warning) {
        PdfPTable note = new PdfPTable(1);
        note.setSpacingAfter(6);
        PdfPCell cell = new PdfPCell(new Phrase(warning, font(bold, 8, BAD)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        note.addCell(cell);
        return note;
    }

    private PdfPTable caption(int part, int parts, List<Metric> metrics, Basis basis) {
        PdfPTable caption = new PdfPTable(1);
        caption.setSpacingBefore(part > 1 ? 10 : 0);
        caption.setSpacingAfter(3);
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("Część " + part + " z " + parts + ": ", font(bold, 8, INK)));
        phrase.add(new Phrase(String.join(", ", metrics.stream().map(metric -> label(metric, basis)).toList()),
                font(regular, 8, INK)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        caption.addCell(cell);
        return caption;
    }

    private PdfPTable table(CounterReport report, List<Metric> metrics, Basis basis, boolean withArticles) {
        int stores = report.stores().size();
        int numbers = metrics.size() * stores;
        float[] widths = new float[1 + numbers];
        widths[0] = NAME_WIDTH;
        for (int i = 1; i < widths.length; i++) {
            widths[i] = (WIDTH - NAME_WIDTH) / numbers;
        }
        PdfPTable table = new PdfPTable(widths);
        table.setHeaderRows(2);
        float size = numbers > 12 ? 6.2f : 7;

        PdfPCell name = headCell("Nomenklatura", Element.ALIGN_LEFT, size);
        name.setRowspan(2);
        name.setVerticalAlignment(Element.ALIGN_BOTTOM);
        table.addCell(name);
        for (Metric metric : metrics) {
            PdfPCell cell = headCell(label(metric, basis), Element.ALIGN_CENTER, size);
            cell.setColspan(stores);
            cell.setBorder(Rectangle.BOTTOM | Rectangle.LEFT);
            cell.setBorderColorLeft(Color.WHITE);
            cell.setBorderWidthLeft(2);
            table.addCell(cell);
        }
        for (int m = 0; m < metrics.size(); m++) {
            for (int s = 0; s < stores; s++) {
                PdfPCell cell = headCell(report.stores().get(s).number(), Element.ALIGN_RIGHT, size);
                if (s == 0 && m > 0) {
                    cell.setBorder(Rectangle.BOTTOM | Rectangle.LEFT);
                    cell.setBorderColorLeft(RULE);
                }
                table.addCell(cell);
            }
        }

        for (CounterReport.Region region : report.regions()) {
            row(table, label(region.code(), region.name()), region.values(), metrics, basis, Kind.REGION, size);
            for (CounterReport.Family family : region.families()) {
                row(table, label(family.code(), family.name()), family.values(), metrics, basis, Kind.FAMILY, size);
                for (CounterReport.Subfamily subfamily : family.subfamilies()) {
                    row(table, label(subfamily.code(), subfamily.name()), subfamily.values(), metrics, basis,
                            Kind.SUBFAMILY, size);
                    if (withArticles) {
                        for (CounterReport.Article article : subfamily.articles()) {
                            String text = Objects.toString(article.name(), "ITM8 " + article.itm8())
                                    + (article.brand() != null && !article.brand().isBlank()
                                            ? " · " + article.brand() : "");
                            row(table, text, article.values(), metrics, basis, Kind.ARTICLE, size);
                        }
                    }
                }
            }
        }
        row(table, "Razem lada", report.total(), metrics, basis, Kind.TOTAL, size);
        return table;
    }

    private enum Kind {
        REGION, FAMILY, SUBFAMILY, ARTICLE, TOTAL
    }

    private void row(PdfPTable table, String label, List<Figures> values, List<Metric> metrics, Basis basis,
            Kind kind, float size) {
        boolean total = kind == Kind.TOTAL;
        BaseFont base = kind == Kind.REGION || kind == Kind.FAMILY || total ? bold : regular;
        Color ink = total ? Color.WHITE : kind == Kind.ARTICLE ? MUTED : INK;
        Color band = total ? TOTAL_BAND : kind == Kind.REGION ? REGION_BAND : kind == Kind.FAMILY ? BAND : null;
        float textSize = kind == Kind.ARTICLE ? size - 0.6f : size;

        PdfPCell name = cell(label, base, textSize, ink, band, Element.ALIGN_LEFT);
        name.setPaddingLeft(kind == Kind.FAMILY ? 8 : kind == Kind.SUBFAMILY ? 14 : kind == Kind.ARTICLE ? 20 : 3);
        table.addCell(name);

        for (int m = 0; m < metrics.size(); m++) {
            Metric metric = metrics.get(m);
            int[] extremes = metric == Metric.MARGIN_PERCENT ? extremes(values) : null;
            for (int s = 0; s < values.size(); s++) {
                Figures figures = values.get(s);
                String text = figures == null ? "–" : format(metric, figures, basis);
                Color color = ink;
                Color background = band;
                BaseFont font = base;
                if (!total && extremes != null && (s == extremes[0] || s == extremes[1])) {
                    boolean best = s == extremes[0];
                    color = best ? GOOD : BAD;
                    background = best ? GOOD_BAND : BAD_BAND;
                    font = bold;
                }
                PdfPCell cell = cell(text, font, textSize, figures == null && !total ? MUTED : color, background,
                        Element.ALIGN_RIGHT);
                if (s == 0 && m > 0) {
                    cell.setBorder(cell.getBorder() | Rectangle.LEFT);
                    cell.setBorderColorLeft(RULE);
                }
                table.addCell(cell);
            }
        }
    }

    /** Sklep z najwyższą i najniższą marżą % w wierszu; bez porównania (mniej niż dwa sklepy z marżą) brak. */
    static int[] extremes(List<Figures> values) {
        int best = -1;
        int worst = -1;
        int known = 0;
        for (int i = 0; i < values.size(); i++) {
            Figures figures = values.get(i);
            if (figures == null || figures.marginPercent() == null) {
                continue;
            }
            known++;
            BigDecimal value = figures.marginPercent();
            if (best < 0 || value.compareTo(values.get(best).marginPercent()) > 0) {
                best = i;
            }
            if (worst < 0 || value.compareTo(values.get(worst).marginPercent()) < 0) {
                worst = i;
            }
        }
        if (known < 2 || values.get(best).marginPercent().compareTo(values.get(worst).marginPercent()) == 0) {
            return null;
        }
        return new int[] {best, worst};
    }

    private PdfPCell headCell(String text, int alignment, float size) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(bold, size - 0.4f, MUTED)));
        cell.setBackgroundColor(BAND);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setPadding(2.5f);
        cell.setLeading(0, 1.1f);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private PdfPCell cell(String text, BaseFont base, float size, Color color, Color background, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(base, size, color)));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        cell.setLeading(0, 1.15f);
        cell.setPadding(1.5f);
        cell.setPaddingBottom(2.5f);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPTable legend(boolean compared) {
        PdfPTable legend = new PdfPTable(1);
        legend.setSpacingBefore(6);
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("Jak czytać: ", font(bold, 6.5f, INK)));
        phrase.add(new Phrase("Kwoty w pełnych złotych, ilość w sztukach i kilogramach razem. Zakup to koszt FIFO "
                + "sprzedanego towaru. Marża = sprzedaż netto − zakup FIFO, % od sprzedaży netto. Udział = sprzedaż "
                + "brutto pozycji w sprzedaży brutto wybranych rejonów lady w sklepie."
                + (compared ? " Na zielono najwyższa, na czerwono najniższa marża % w wierszu." : "")
                + " „–” = brak sprzedaży w sklepie.", font(regular, 6.5f, MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColor(RULE);
        cell.setPadding(0);
        cell.setPaddingTop(4);
        cell.setLeading(0, 1.3f);
        legend.addCell(cell);
        return legend;
    }


    static String label(Metric metric, Basis basis) {
        return switch (metric) {
            case QUANTITY -> "Ilość";
            case COST -> "Zakup FIFO";
            case SALE -> basis == Basis.NET ? "Sprzedaż netto" : "Sprzedaż brutto";
            case MARGIN -> "Marża zł";
            case MARGIN_PERCENT -> "Marża %";
            case SHARE -> "Udział %";
        };
    }

    private static String format(Metric metric, Figures figures, Basis basis) {
        return switch (metric) {
            case QUANTITY -> format("#,##0.#", figures.quantity());
            case COST -> whole(figures.costValue());
            case SALE -> whole(basis == Basis.NET ? figures.saleValueNet() : figures.saleValueGross());
            case MARGIN -> whole(figures.marginValue());
            case MARGIN_PERCENT -> percent(figures.marginPercent());
            case SHARE -> percent(figures.sharePercent());
        };
    }

    private static String label(String code, String name) {
        String shown = code.length() == 6 ? code.substring(0, 2) + " " + code.substring(2, 4) + " " + code.substring(4)
                : code.length() == 4 ? code.substring(0, 2) + " " + code.substring(2) : code;
        return shown + "  " + (name != null ? name : "brak nazwy w nomenklaturze");
    }

    /** Ostrzeżenie o sklepach, które mają sprzedaż wgraną krócej niż do końca okresu. */
    static String warning(CounterReport report) {
        List<String> late = report.stores().stream()
                .filter(store -> store.lastSaleDate() == null || store.lastSaleDate().isBefore(report.to()))
                .map(store -> store.number() + " (" + (store.lastSaleDate() != null
                        ? "do " + DATE.format(store.lastSaleDate()) : "brak sprzedaży") + ")")
                .toList();
        return late.isEmpty() ? null
                : "Uwaga: sprzedaż nie jest wgrana za cały okres w sklepach " + String.join(", ", late) + ".";
    }

    private static String period(CounterReport report) {
        return report.from().equals(report.to()) ? DATE.format(report.from())
                : DATE.format(report.from()) + " – " + DATE.format(report.to());
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private static String money(BigDecimal value) {
        return value == null ? "–" : format("#,##0.00", value);
    }

    private static String whole(BigDecimal value) {
        return value == null ? "–" : format("#,##0", value);
    }

    private static String percent(BigDecimal value) {
        return value == null ? "–" : format("0.0", value) + "%";
    }

    private static String format(String pattern, BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat(pattern, symbols).format(value);
    }
}
