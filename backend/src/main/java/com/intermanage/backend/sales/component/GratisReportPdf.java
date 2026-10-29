package com.intermanage.backend.sales.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.sales.dto.GratisReportResponse;
import com.intermanage.backend.sales.dto.GratisReportResponse.Figures;
import com.intermanage.backend.sales.dto.GratisReportResponse.Region;
import com.intermanage.backend.sales.dto.GratisReportResponse.Tile;
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
 * Wydruk raportu przyjętych gratisów: A4 pionowo, kafelki w dwóch kolumnach. Strona mieści 6 kafelków (3 rzędy),
 * a gdy któryś z nich ma za dużo rejonów - 4 (2 rzędy), w razie potrzeby proporcjonalnie zmniejszone. Najpierw
 * podsumowanie okresu, potem miesiące.
 */
@Component
public class GratisReportPdf {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final Color TOTAL_BAND = new Color(39, 39, 42);
    private static final Color SUMMARY_BAND = new Color(209, 250, 229);

    private static final Rectangle PAGE = PageSize.A4;
    private static final float MARGIN = 24;
    private static final float MARGIN_BOTTOM = 40;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    /** Wysokość na treść, z zapasem na zaokrąglenia przy składaniu strony. */
    private static final float HEIGHT = PAGE.getHeight() - MARGIN - MARGIN_BOTTOM - 6;
    /** Połowa odstępu między kafelkami (dopełnienie komórki siatki). */
    private static final float GAP = 4;
    private static final float TILE_WIDTH = WIDTH / 2 - 2 * GAP;
    private static final float MIN_SCALE = 0.4f;

    private static final String[] HEADERS = {"Rejon", "Handel /\nekspozycja", "Pokrycie\nstrat", "Obrót\nbrutto",
            "% obrotu"};
    private static final float[] WIDTHS = {38, 16, 16, 17, 11};

    private final BaseFont regular;
    private final BaseFont bold;

    public GratisReportPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(GratisReportResponse report, String printedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PAGE, MARGIN, MARGIN, MARGIN,
                MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            String footer = "Raport przyjętych gratisów · sklep " + report.storeNumber() + " · " + period(report)
                    + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(STORE_ZONE))
                    + (printedBy != null ? " · " + printedBy : "");
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), footer));
            document.addTitle("Raport przyjętych gratisów " + period(report));
            document.addCreator("Intermanage");
            document.open();

            List<Tile> tiles = new ArrayList<>();
            if (report.summary() != null) {
                tiles.add(report.summary());
            }
            tiles.addAll(report.months());
            PdfPTable header = header(report);
            float slots = HEIGHT - header.getTotalHeight() - header.spacingAfter();
            for (int start = 0; start < tiles.size(); ) {
                if (start > 0) {
                    document.newPage();
                }
                document.add(header(report));
                Page page = page(tiles, start, slots);
                document.add(page.grid());
                start += page.count();
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku raportu gratisów", e);
        }
        return out.toByteArray();
    }

    private record Page(PdfPTable grid, int count) {
    }

    /** Strona od kafelka {@code start}: 6, gdy wszystkie mieszczą się w trzech rzędach, inaczej 4 (zmniejszone). */
    private Page page(List<Tile> tiles, int start, float height) {
        List<Tile> six = tiles.subList(start, Math.min(start + 6, tiles.size()));
        float sixSlot = height / 3;
        if (fits(six, 1, sixSlot)) {
            return new Page(grid(six, 1, sixSlot), six.size());
        }
        List<Tile> four = tiles.subList(start, Math.min(start + 4, tiles.size()));
        float fourSlot = height / 2;
        float scale = 1;
        while (scale > MIN_SCALE && !fits(four, scale, fourSlot)) {
            scale -= 0.05f;
        }
        return new Page(grid(four, scale, fourSlot), four.size());
    }

    private boolean fits(List<Tile> tiles, float scale, float slot) {
        return tiles.stream().allMatch(tile -> tile(tile, scale).getTotalHeight() <= slot - 2 * GAP);
    }

    /** Siatka 2 kolumny × rzędy o stałej wysokości; pusta komórka dopełnia nieparzystą liczbę kafelków. */
    private PdfPTable grid(List<Tile> tiles, float scale, float slot) {
        PdfPTable grid = new PdfPTable(2);
        grid.setTotalWidth(WIDTH);
        grid.setLockedWidth(true);
        for (Tile tile : tiles) {
            PdfPCell cell = new PdfPCell();
            cell.addElement(tile(tile, scale));
            slot(cell, slot);
            grid.addCell(cell);
        }
        if (tiles.size() % 2 == 1) {
            PdfPCell empty = new PdfPCell(new Phrase(""));
            slot(empty, slot);
            grid.addCell(empty);
        }
        return grid;
    }

    private static void slot(PdfPCell cell, float height) {
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(GAP);
        cell.setFixedHeight(height);
    }

    private PdfPTable header(GratisReportResponse report) {
        PdfPTable header = new PdfPTable(1);
        header.setTotalWidth(WIDTH);
        header.setLockedWidth(true);
        header.setSpacingAfter(4);
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("Raport przyjętych gratisów\n", font(bold, 14, INK)));
        phrase.add(new Phrase("Sklep " + report.storeNumber() + " · " + report.storeName() + " · ",
                font(regular, 9, INK)));
        phrase.add(new Phrase(period(report) + "\n", font(bold, 9, INK)));
        phrase.add(new Phrase("Wartość gratisu to sprzedaż brutto przeniesień stanów zarejestrowanych jako gratis, "
                + "w miesiącu gratisu. Obrót to sprzedaż brutto rejonu w tym okresie, w wierszu Razem całego sklepu; "
                + "% obrotu = gratisy razem / obrót.", font(regular, 6.5f, MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingLeft(GAP);
        cell.setPaddingBottom(4);
        cell.setLeading(0, 1.3f);
        header.addCell(cell);
        return header;
    }

    /** Kafelek: tytuł z sumą gratisów, nagłówek kolumn, rejony i wiersz Razem. */
    private PdfPTable tile(Tile tile, float scale) {
        boolean summary = !tile.from().equals(tile.to());
        PdfPTable table = new PdfPTable(WIDTHS);
        table.setTotalWidth(TILE_WIDTH);
        table.setLockedWidth(true);
        table.setWidthPercentage(100);

        Phrase title = new Phrase();
        title.add(new Phrase(tile.label(), font(bold, 9.5f * scale, INK)));
        title.add(new Phrase("   " + documents(tile.documentCount()), font(regular, 7 * scale, MUTED)));
        PdfPCell titleCell = new PdfPCell(title);
        titleCell.setColspan(3);
        band(titleCell, summary, scale);
        table.addCell(titleCell);
        PdfPCell sumCell = new PdfPCell(new Phrase(money(tile.total().gratisValue()) + " zł",
                font(bold, 9.5f * scale, INK)));
        sumCell.setColspan(2);
        sumCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        band(sumCell, summary, scale);
        table.addCell(sumCell);

        for (int i = 0; i < HEADERS.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(HEADERS[i], font(bold, 5.8f * scale, MUTED)));
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            cell.setPadding(1.5f * scale);
            cell.setPaddingBottom(2.5f * scale);
            cell.setLeading(0, 1.1f);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            table.addCell(cell);
        }
        if (tile.regions().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Brak gratisów", font(regular, 6.8f * scale, MUTED)));
            empty.setColspan(HEADERS.length);
            empty.setBorder(Rectangle.BOTTOM);
            empty.setBorderColor(RULE);
            empty.setPadding(3 * scale);
            table.addCell(empty);
        }
        for (Region region : tile.regions()) {
            String label = region.code().isEmpty() ? region.name()
                    : region.code() + "  " + (region.name() != null ? region.name() : "");
            row(table, label, region.figures(), scale, false);
        }
        row(table, "Razem", tile.total(), scale, true);
        return table;
    }

    private void band(PdfPCell cell, boolean summary, float scale) {
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(summary ? SUMMARY_BAND : BAND);
        cell.setPadding(3 * scale);
        cell.setPaddingBottom(4.5f * scale);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
    }

    private void row(PdfPTable table, String label, Figures figures, float scale, boolean total) {
        String[] values = {label, money(figures.tradeValue()), money(figures.lossValue()),
                whole(figures.saleValueGross()), percent(figures.gratisPercent())};
        for (int i = 0; i < values.length; i++) {
            Color color = total ? Color.WHITE : i == 3 ? MUTED : INK;
            PdfPCell cell = new PdfPCell(new Phrase(values[i], font(total ? bold : regular, 6.6f * scale, color)));
            cell.setBorder(total ? Rectangle.NO_BORDER : Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            if (total) {
                cell.setBackgroundColor(TOTAL_BAND);
            }
            cell.setLeading(0, 1.15f);
            cell.setPadding(1.5f * scale);
            cell.setPaddingBottom((total ? 3.5f : 2.5f) * scale);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(cell);
        }
    }

    private static String period(GratisReportResponse report) {
        return report.summary() != null ? report.summary().label() : report.months().getFirst().label();
    }

    private static String documents(long count) {
        if (count == 1) {
            return "1 gratis";
        }
        long lastTwo = count % 100;
        long last = count % 10;
        return count + (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? " gratisy" : " gratisów");
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
        return value == null ? "–" : format("0.00", value) + "%";
    }

    private static String format(String pattern, BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat(pattern, symbols).format(value);
    }
}
