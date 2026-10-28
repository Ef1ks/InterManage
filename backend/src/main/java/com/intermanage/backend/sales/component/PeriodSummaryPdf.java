package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Figures;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Kind;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Line;
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
 * Wydruk podsumowania sprzedaży i strat (A4 poziomo): nagłówek z okresem i kafelkami sum sklepu, tabela grup
 * i rejonów z budżetem obok wartości (procent w budżecie na zielono, poza budżetem na czerwono), na końcu objaśnienia
 * kolumn. Nagłówek tabeli powtarza się na każdej stronie, a wiersze nie dzielą się między strony.
 */
@Component
public class PeriodSummaryPdf {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final Color GROUP_BAND = new Color(228, 228, 231);
    private static final Color TOTAL_BAND = new Color(39, 39, 42);
    private static final Color BAD = new Color(190, 18, 60);
    private static final Color BAD_BAND = new Color(255, 228, 230);
    private static final Color GOOD = new Color(4, 120, 87);
    private static final Color GOOD_BAND = new Color(209, 250, 229);

    /** Kolumny: nazwa, liczby; szerokości względne. */
    private static final String[] HEADERS = {"Rejon", "Sprzedaż\nnetto", "Sprzedaż\nbrutto", "Zakup\nFIFO",
            "Marża\nzł", "Marża\n%", "Budżet\nmarży", "Straty\nzł", "Straty\n%", "Max\nstrat",
            "Marża po\nstratach zł", "Marża po\nstratach %", "Udział\n%"};
    private static final float[] WIDTHS = {23, 8.5f, 8.5f, 8.5f, 8, 6, 5.5f, 7.5f, 6, 5.5f, 8.5f, 7, 5.5f};

    private final BaseFont regular;
    private final BaseFont bold;

    public PeriodSummaryPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(PeriodSummaryResponse summary, String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4.rotate(), 28, 28, 28, 46);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Podsumowanie sprzedaży i strat · sklep " + summary.storeNumber() + " · "
                    + period(summary) + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(STORE_ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Podsumowanie sprzedaży i strat " + period(summary));
            document.addCreator("Intermanage");
            document.open();
            writeHeader(document, summary);
            document.add(table(summary));
            writeLegend(document);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku podsumowania", e);
        }
        return out.toByteArray();
    }

    private void writeHeader(com.lowagie.text.Document document, PeriodSummaryResponse summary)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] {34, 16.5f, 16.5f, 16.5f, 16.5f});
        header.setWidthPercentage(100);
        header.setSpacingAfter(12);

        Phrase title = new Phrase();
        title.add(new Phrase("Podsumowanie sprzedaży i strat\n", font(bold, 15, INK)));
        title.add(new Phrase("Sklep " + summary.storeNumber() + " · " + summary.storeName() + "\n",
                font(regular, 9, INK)));
        long days = ChronoUnit.DAYS.between(summary.from(), summary.to()) + 1;
        title.add(new Phrase(period(summary) + " (" + days + (days == 1 ? " dzień" : " dni") + ")",
                font(bold, 9, INK)));
        PdfPCell titleCell = new PdfPCell(title);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setLeading(0, 1.35f);
        titleCell.setPaddingLeft(0);
        header.addCell(titleCell);

        Figures total = summary.total();
        header.addCell(tile("Sprzedaż brutto", money(total.saleValueGross()) + " zł",
                "netto " + money(total.saleValueNet()) + " zł", false));
        header.addCell(tile("Marża", percent(total.marginPercent()), money(total.marginValue()) + " zł", false));
        header.addCell(tile("Straty (likwidacje)", percent(total.lossPercent()),
                money(total.lossValue()) + " zł", false));
        header.addCell(tile("Marża po stratach", percent(total.marginAfterLossesPercent()),
                money(total.marginAfterLossesValue()) + " zł", true));
        document.add(header);

        String warning = warning(summary);
        if (warning != null) {
            PdfPTable note = new PdfPTable(1);
            note.setWidthPercentage(100);
            note.setSpacingAfter(8);
            PdfPCell cell = new PdfPCell(new Phrase(warning, font(bold, 8, BAD)));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingLeft(0);
            note.addCell(cell);
            document.add(note);
        }
    }

    private PdfPCell tile(String label, String value, String detail, boolean strong) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label.toUpperCase(POLISH) + "\n", font(bold, 6.5f, strong ? Color.WHITE : MUTED)));
        phrase.add(new Phrase(value + "\n", font(bold, 13, strong ? Color.WHITE : INK)));
        phrase.add(new Phrase(detail, font(regular, 7.5f, strong ? new Color(212, 212, 216) : MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        // Biała lewa krawędź rozdziela kafelki.
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderWidthLeft(4);
        cell.setBorderColorLeft(Color.WHITE);
        cell.setBackgroundColor(strong ? TOTAL_BAND : BAND);
        cell.setPadding(7);
        cell.setLeading(0, 1.3f);
        return cell;
    }

    private PdfPTable table(PeriodSummaryResponse summary) {
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitLate(true);
        for (int i = 0; i < HEADERS.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(HEADERS[i], font(bold, 6.8f, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            cell.setPadding(4);
            cell.setLeading(0, 1.15f);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            table.addCell(cell);
        }
        for (Line line : summary.lines()) {
            switch (line.kind()) {
                case GROUP -> groupRow(table, line);
                case REGION -> figuresRow(table, line.depth(), regionLabel(line), line.figures(),
                        line.marginBudgetPercent(), line.maxLossPercent(), Kind.REGION);
                case SUBTOTAL -> figuresRow(table, line.depth(), line.name(), line.figures(), null, null,
                        Kind.SUBTOTAL);
            }
        }
        totalRow(table, summary.total());
        return table;
    }

    private void groupRow(PdfPTable table, Line line) {
        PdfPCell cell = new PdfPCell(new Phrase(line.name().toUpperCase(POLISH),
                font(bold, line.depth() == 0 ? 8 : 7.5f, INK)));
        cell.setColspan(HEADERS.length);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(line.depth() == 0 ? GROUP_BAND : BAND);
        cell.setPadding(4);
        cell.setPaddingTop(line.depth() == 0 ? 7 : 5);
        cell.setPaddingLeft(4 + line.depth() * 9);
        table.addCell(cell);
    }

    private void figuresRow(PdfPTable table, int depth, String label, Figures figures, BigDecimal marginBudget,
            BigDecimal maxLoss, Kind kind) {
        boolean subtotal = kind == Kind.SUBTOTAL;
        BaseFont base = subtotal ? bold : regular;
        // Względem budżetu: null = bez budżetu, true = w budżecie, false = poza nim.
        Boolean marginOk = marginBudget == null || figures.marginPercent() == null ? null
                : figures.marginPercent().compareTo(marginBudget) >= 0;
        Boolean lossOk = maxLoss == null || figures.lossPercent() == null ? null
                : figures.lossPercent().compareTo(maxLoss) <= 0;

        PdfPCell name = new PdfPCell(new Phrase(label, font(base, 7.8f, INK)));
        name.setPaddingLeft(4 + depth * 9);
        table.addCell(style(name, subtotal, Element.ALIGN_LEFT));
        table.addCell(number(money(figures.saleValueNet()), base, INK, subtotal));
        table.addCell(number(money(figures.saleValueGross()), base, INK, subtotal));
        table.addCell(number(money(figures.costValue()), base, MUTED, subtotal));
        table.addCell(number(money(figures.marginValue()), base, INK, subtotal));
        table.addCell(budgeted(percent(figures.marginPercent()), marginOk, base, subtotal));
        table.addCell(budgeted(budget(marginBudget), marginOk, regular, subtotal));
        table.addCell(number(money(figures.lossValue()), base, INK, subtotal));
        table.addCell(budgeted(percent(figures.lossPercent()), lossOk, base, subtotal));
        table.addCell(budgeted(budget(maxLoss), lossOk, regular, subtotal));
        table.addCell(number(money(figures.marginAfterLossesValue()), bold, INK, subtotal));
        table.addCell(number(percent(figures.marginAfterLossesPercent()), bold, INK, subtotal));
        table.addCell(number(percent(figures.sharePercent()), base, MUTED, subtotal));
    }

    private void totalRow(PdfPTable table, Figures total) {
        String[] values = {"Razem sklep", money(total.saleValueNet()), money(total.saleValueGross()),
                money(total.costValue()), money(total.marginValue()), percent(total.marginPercent()), "",
                money(total.lossValue()), percent(total.lossPercent()), "",
                money(total.marginAfterLossesValue()), percent(total.marginAfterLossesPercent()), "100%"};
        for (int i = 0; i < values.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(values[i], font(bold, 8, Color.WHITE)));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setBackgroundColor(TOTAL_BAND);
            cell.setPadding(5);
            cell.setPaddingBottom(6);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    /** Procent albo budżet: tło zielone w budżecie, czerwone poza nim, bez tła, gdy budżetu nie ma. */
    private PdfPCell budgeted(String value, Boolean ok, BaseFont base, boolean subtotal) {
        if (ok == null) {
            return number(value, base, base == regular && value.isEmpty() ? MUTED : INK, subtotal);
        }
        PdfPCell cell = number(value, bold, ok ? GOOD : BAD, subtotal);
        cell.setBackgroundColor(ok ? GOOD_BAND : BAD_BAND);
        return cell;
    }

    private PdfPCell number(String value, BaseFont base, Color color, boolean subtotal) {
        return style(new PdfPCell(new Phrase(value, font(base, 7.8f, color))), subtotal, Element.ALIGN_RIGHT);
    }

    private static PdfPCell style(PdfPCell cell, boolean subtotal, int alignment) {
        cell.setBorder(subtotal ? Rectangle.TOP | Rectangle.BOTTOM : Rectangle.BOTTOM);
        cell.setBorderColor(subtotal ? MUTED : RULE);
        cell.setPadding(3.5f);
        cell.setPaddingBottom(4.5f);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private void writeLegend(com.lowagie.text.Document document) throws DocumentException {
        PdfPTable legend = new PdfPTable(1);
        legend.setWidthPercentage(100);
        legend.setSpacingBefore(10);
        legend.setKeepTogether(true);
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("Jak czytać: ", font(bold, 7, INK)));
        phrase.add(new Phrase("Marża % od sprzedaży netto. Straty to likwidacje w cenach zakupu, % od sprzedaży "
                + "brutto. Marża po stratach = marża − straty, % od sprzedaży netto. Udział = sprzedaż brutto rejonu w sprzedaży brutto sklepu. Budżet marży "
                + "i max strat ustawia się w Ustawieniach sklepu: na zielono procent w budżecie, na czerwono marża "
                + "poniżej budżetu i straty powyżej limitu.", font(regular, 7, MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColor(RULE);
        cell.setPaddingTop(6);
        cell.setPaddingLeft(0);
        cell.setLeading(0, 1.4f);
        legend.addCell(cell);
        document.add(legend);
    }

    private static String regionLabel(Line line) {
        if (line.code() == null || line.code().isEmpty()) {
            return line.name();
        }
        return line.code() + "  " + (line.name() != null ? line.name() : "");
    }

    /** Ostrzeżenie, gdy okres wychodzi poza wgraną sprzedaż albo dokumenty. */
    static String warning(PeriodSummaryResponse summary) {
        if (summary.lastSaleDate() == null || summary.to().isAfter(summary.lastSaleDate())) {
            return "Uwaga: sprzedaż jest wgrana tylko do "
                    + (summary.lastSaleDate() != null ? DATE.format(summary.lastSaleDate()) : "– (brak sprzedaży)")
                    + ", okres jest niepełny.";
        }
        if (summary.documentsCoveredUntil() == null || summary.to().isAfter(summary.documentsCoveredUntil())) {
            return "Uwaga: likwidacje są wgrane tylko do "
                    + (summary.documentsCoveredUntil() != null ? DATE.format(summary.documentsCoveredUntil()) : "–")
                    + ", straty mogą być niepełne.";
        }
        return null;
    }

    private static String period(PeriodSummaryResponse summary) {
        return summary.from().equals(summary.to()) ? DATE.format(summary.from())
                : DATE.format(summary.from()) + " – " + DATE.format(summary.to());
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private static String money(BigDecimal value) {
        return value == null ? "–" : format("#,##0.00", value);
    }

    private static String percent(BigDecimal value) {
        return value == null ? "–" : format("0.0", value) + "%";
    }

    private static String budget(BigDecimal value) {
        return value == null ? "" : format("0.##", value) + "%";
    }

    private static String format(String pattern, BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat(pattern, symbols).format(value);
    }
}
