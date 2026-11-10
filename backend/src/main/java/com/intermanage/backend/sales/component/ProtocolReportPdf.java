package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.sales.dto.ProtocolReportGrouping;
import com.intermanage.backend.sales.dto.ProtocolReportResponse;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Article;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Figures;
import com.intermanage.backend.sales.dto.ProtocolReportResponse.Group;
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
 * Wydruk raportu protokołu dla wybranych grup (marek, dostawców, pozycji nomenklatury albo artykułów), np. żeby
 * pokazać dostawcy, co z jego towaru poszło do likwidacji. Bez porównania A4 pionowo, z porównaniem poziomo (trzy
 * kolumny na miarę). Pod grupą jej artykuły, gdy {@code articles}.
 */
@Component
public class ProtocolReportPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private static final float MARGIN = 28;
    private static final float MARGIN_BOTTOM = 40;
    private static final float CELL_PADDING = 3;
    /** Tyle nazw wybranych grup wypisujemy w nagłówku, przy większej liczbie sama liczba. */
    private static final int NAMED_GROUPS = 12;

    private record Metric(String label, Function<Figures, BigDecimal> value, boolean money) {
    }

    private static final List<Metric> METRICS = List.of(
            new Metric("Ilość", Figures::quantity, false),
            new Metric("Zakup netto", Figures::purchaseValue, true),
            new Metric("Sprzedaż brutto", Figures::saleValueGross, true));

    /**
     * Opis wydruku z serwisu: nazwa protokołu, sklep, grupowanie, kto drukuje i dostawcy do kontroli strat (własne
     * grupy artykułów sklepu), których dotyczy wydruk.
     */
    public record Header(String protocol, String storeNumber, String storeName, String grouping, String compareLabel,
            boolean selection, String printedBy, List<String> controlGroups) {
    }

    private final BaseFont regular;
    private final BaseFont bold;

    public ProtocolReportPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code groups} to wybrane grupy raportu, {@code total} i {@code previousTotal} ich sumy. */
    public byte[] pdf(Header header, ProtocolReportResponse report, List<Group> groups, Figures total,
            Figures previousTotal, boolean articles) {
        boolean compare = previousTotal != null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(
                compare ? PageSize.A4.rotate() : PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Protokół: " + header.protocol() + " · sklep " + header.storeNumber() + " · wydruk "
                    + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))
                    + (header.printedBy() != null ? " · " + header.printedBy() : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Protokół: " + header.protocol());
            document.addCreator("Intermanage");
            document.open();
            document.add(title(header, report, groups, total, compare));
            document.add(table(header, report, groups, total, previousTotal, articles));
            document.add(signatures());
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku raportu protokołów", e);
        }
        return out.toByteArray();
    }

    private Paragraph title(Header header, ProtocolReportResponse report, List<Group> groups, Figures total,
            boolean compare) {
        Paragraph title = new Paragraph();
        title.setSpacingAfter(10);
        title.add(new Phrase("Protokół: " + header.protocol() + "\n", font(bold, 14, INK)));
        title.add(new Phrase("Sklep " + header.storeNumber() + " · " + header.storeName() + "\n",
                font(regular, 9, INK)));
        String period = "Okres " + range(report.from(), report.to()) + " (wg daty dokumentu)";
        if (compare) {
            period += " · porównanie z " + range(report.previousFrom(), report.previousTo());
        }
        title.add(new Phrase(period + "\n", font(regular, 8.5f, INK)));
        if (header.controlGroups() != null && !header.controlGroups().isEmpty()) {
            title.add(new Phrase((header.controlGroups().size() == 1 ? "Dostawca: " : "Dostawcy: ")
                    + String.join(", ", header.controlGroups()) + "\n", font(bold, 11, INK)));
        }
        if (header.selection()) {
            title.add(new Phrase(header.grouping() + ": " + selected(groups) + "\n", font(bold, 9, INK)));
        } else {
            title.add(new Phrase("Grupowanie: " + header.grouping().toLowerCase(POLISH) + "\n",
                    font(regular, 8.5f, INK)));
        }
        title.add(new Phrase((header.selection() ? "Razem zaznaczone: ilość " : "Razem: ilość ") + quantity(total.quantity()) + " · zakup netto "
                + money(total.purchaseValue()) + " zł · sprzedaż brutto " + money(total.saleValueGross()) + " zł",
                font(bold, 9, INK)));
        return title;
    }

    private static String selected(List<Group> groups) {
        List<String> names = groups.stream().limit(NAMED_GROUPS).map(ProtocolReportPdf::groupLabel).toList();
        String text = String.join(", ", names);
        return groups.size() > NAMED_GROUPS ? text + " i " + (groups.size() - NAMED_GROUPS) + " innych" : text;
    }

    private PdfPTable table(Header header, ProtocolReportResponse report, List<Group> groups, Figures total,
            Figures previousTotal, boolean articles) throws DocumentException {
        boolean compare = previousTotal != null;
        boolean byArticle = report.grouping() == ProtocolReportGrouping.ARTICLE;
        int numberColumns = METRICS.size() * (compare ? 3 : 1);
        float[] widths = new float[1 + numberColumns];
        widths[0] = compare ? 210 : 250;
        for (int column = 1; column < widths.length; column++) {
            widths[column] = compare ? 58 : 80;
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);

        if (compare) {
            table.addCell(headCell("", Element.ALIGN_LEFT));
            for (Metric metric : METRICS) {
                PdfPCell cell = headCell(metric.label(), Element.ALIGN_CENTER);
                cell.setColspan(3);
                table.addCell(cell);
            }
        }
        table.addCell(headCell(byArticle ? "Artykuł" : header.grouping() + (articles ? " / artykuł" : ""),
                Element.ALIGN_LEFT));
        for (Metric metric : METRICS) {
            if (compare) {
                table.addCell(headCell(header.compareLabel(), Element.ALIGN_RIGHT));
                table.addCell(headCell("Aktualny", Element.ALIGN_RIGHT));
                table.addCell(headCell("Zmiana", Element.ALIGN_RIGHT));
            } else {
                table.addCell(headCell(metric.label(), Element.ALIGN_RIGHT));
            }
        }
        table.setHeaderRows(compare ? 2 : 1);

        for (Group group : groups) {
            if (byArticle) {
                Article article = group.articles().getFirst();
                table.addCell(articleCell(article, 0));
                figures(table, article.current(), article.previous(), compare, false, null);
                continue;
            }
            boolean nested = articles && !group.articles().isEmpty();
            PdfPCell name = cell(new Phrase(groupLabel(group) + "  ", font(bold, 8, INK)));
            name.getPhrase().add(new Phrase(articlesLabel(group.articles().size()), font(regular, 7, MUTED)));
            Color background = nested ? BAND : null;
            name.setBackgroundColor(background);
            table.addCell(name);
            figures(table, group.current(), group.previous(), compare, true, background);
            if (nested) {
                for (Article article : group.articles()) {
                    table.addCell(articleCell(article, 10));
                    figures(table, article.current(), article.previous(), compare, false, null);
                }
            }
        }

        PdfPCell sum = cell(new Phrase("Razem", font(bold, 8.5f, INK)));
        sum.setBorder(Rectangle.TOP);
        sum.setBorderWidth(1);
        sum.setBorderColor(INK);
        table.addCell(sum);
        for (Metric metric : METRICS) {
            List<String> values = new ArrayList<>();
            if (compare) {
                values.add(format(metric, metric.value().apply(previousTotal)));
                values.add(format(metric, metric.value().apply(total)));
                values.add(change(metric.value().apply(total), metric.value().apply(previousTotal)));
            } else {
                values.add(format(metric, metric.value().apply(total)));
            }
            for (String value : values) {
                PdfPCell cell = number(value, font(bold, 8, INK), null);
                cell.setBorder(Rectangle.TOP);
                cell.setBorderWidth(1);
                cell.setBorderColor(INK);
                table.addCell(cell);
            }
        }
        return table;
    }

    private void figures(PdfPTable table, Figures current, Figures previous, boolean compare, boolean strong,
            Color background) {
        Font main = font(strong ? bold : regular, 7.5f, INK);
        Font muted = font(regular, 7.5f, MUTED);
        for (Metric metric : METRICS) {
            BigDecimal value = metric.value().apply(current);
            if (compare && previous != null) {
                BigDecimal before = metric.value().apply(previous);
                table.addCell(number(format(metric, before), muted, background));
                table.addCell(number(format(metric, value), main, background));
                table.addCell(number(change(value, before), muted, background));
            } else {
                table.addCell(number(format(metric, value), main, background));
            }
        }
    }

    private PdfPCell articleCell(Article article, float indent) {
        Phrase phrase = new Phrase(article.name() != null ? article.name() : itm8(article.itm8()),
                font(regular, 7.5f, INK));
        String codes = String.join(" · ", Stream.of(itm8(article.itm8()), article.ean(),
                article.brand()).filter(value -> value != null && !value.isBlank()).toList());
        phrase.add(new Phrase("\n" + codes, font(regular, 6.5f, MUTED)));
        PdfPCell cell = cell(phrase);
        cell.setPaddingLeft(CELL_PADDING + indent);
        return cell;
    }

    /** Miejsce na podpisy, bo wydruk idzie do dostawcy; tabela nie dzieli się między strony. */
    private PdfPTable signatures() {
        PdfPTable signatures = new PdfPTable(new float[] {1, 0.4f, 1});
        signatures.setWidthPercentage(80);
        signatures.setSpacingBefore(40);
        signatures.setKeepTogether(true);
        for (String label : List.of("Podpis przedstawiciela sklepu", "", "Podpis przedstawiciela dostawcy")) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font(regular, 7.5f, MUTED)));
            cell.setBorder(label.isEmpty() ? Rectangle.NO_BORDER : Rectangle.TOP);
            cell.setBorderColor(MUTED);
            cell.setBorderWidth(0.5f);
            cell.setPaddingTop(4);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            signatures.addCell(cell);
        }
        return signatures;
    }

    private PdfPCell headCell(String text, int align) {
        PdfPCell cell = cell(new Phrase(text != null ? text : "", font(bold, 7, MUTED)));
        cell.setBackgroundColor(BAND);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static PdfPCell number(String value, Font font, Color background) {
        PdfPCell cell = cell(new Phrase(value, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setBackgroundColor(background);
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

    private static String groupLabel(Group group) {
        String name = group.name() != null ? group.name().replaceFirst("^\\d+_", "") : "";
        return group.code() != null && !group.code().equals(name) ? group.code() + " " + name : name;
    }

    private static String articlesLabel(int count) {
        if (count == 1) {
            return "1 artykuł";
        }
        int lastTwo = count % 100;
        int last = count % 10;
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? count + " artykuły" : count + " artykułów";
    }

    private static String format(Metric metric, BigDecimal value) {
        return metric.money() ? money(value) : quantity(value);
    }

    private static String change(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return current.signum() == 0 ? "–" : "nowe";
        }
        BigDecimal percent = current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 1, RoundingMode.HALF_UP);
        return (percent.signum() > 0 ? "+" : "") + percent.toPlainString().replace('.', ',') + "%";
    }

    private static String itm8(String value) {
        return value.replaceFirst("^0+", "");
    }

    private static String quantity(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(POLISH)).format(value)
                : "";
    }

    private static String money(BigDecimal value) {
        return value != null ? new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value)
                : "";
    }

    private static String range(LocalDate from, LocalDate to) {
        return DATE.format(from) + " – " + DATE.format(to);
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
