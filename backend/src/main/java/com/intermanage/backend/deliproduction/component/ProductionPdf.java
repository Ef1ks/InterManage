package com.intermanage.backend.deliproduction.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfBarcodes;
import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Wydruki kart produkcji (A4 pionowo):
 * <ul>
 * <li>karta produkcji z czasem schładzania CCP1 w układzie dawnego arkusza:
 * pieczątka, tytuł, data, tabela
 * 11 kolumn po {@link #CARD_ROWS} wierszy na stronie (puste wiersze do końca
 * strony) i stopka dokumentu GMP z podpisem;
 * każda strona ma pełny nagłówek i stopkę, jak kolejne arkusze "nr 2" w pliku
 * Google;</li>
 * <li>formularz do uzupełnienia: artykuły bazy alfabetycznie w dwóch tabelach
 * obok siebie (nazwa, puste pole na
 * ilość, kod kreskowy do zeskanowania).</li>
 * </ul>
 */
@Component
public class ProductionPdf {

    public static final int CARD_ROWS = 27;
    static final int FORM_ROWS = 20;

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("d.MM.yyyy", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(120, 120, 120);
    private static final Color LIGHT_RULE = new Color(200, 200, 200);
    private static final Color BAND = new Color(244, 244, 245);

    private static final String[] CARD_HEADERS = {
            "Asortyment",
            "Godzina\nrozpoczęcia\nprocesu",
            "Godzina\nzakończenia\nprocesu",
            "Temperatura\nw czasie\nprocesu",
            "Temperatura\nwewnętrzna\npo wypieku",
            "Ilość",
            "Godzina\nprzekazania\ndo schłodzenia/\ngodzina\nprzekazania\ndo lady",
            "Czas\nschładzania",
            "Temperatura\nschładzania",
            "Ważność",
            "Niezgodności\ndziałania\nkorygujące",
    };
    /**
     * Wąskie kolumny mieszczą najdłuższe słowo nagłówka ("Niezgodności") bez
     * dzielenia, nazwa bierze resztę.
     */
    private static final float[] CARD_WIDTHS = { 110, 40, 43, 46, 46, 28, 42, 40, 46, 50, 48 };

    private final BaseFont regular;
    private final BaseFont bold;

    public ProductionPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** Karta do wydruku; {@code stamp} to linie pieczątki zakładu. */
    public record Card(LocalDate productionDate, List<String> stamp, List<CardLine> lines) {
    }

    public record CardLine(String name, int startHour, int endHour, Integer bakingTemperature,
            Integer internalTemperature, BigDecimal quantity, Integer coolingTime, Integer coolingTemperature,
            LocalDate expiryDate) {
    }

    /** Formularz do uzupełnienia: artykuły już ułożone alfabetycznie. */
    public record Form(String storeLabel, List<FormLine> lines) {
    }

    public record FormLine(String name, String code) {
    }

    public byte[] card(Card card) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 28, 28, 28, 28);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("Karta produkcji " + DATE.format(card.productionDate()));
            document.open();
            int pages = Math.max(1, (card.lines().size() + CARD_ROWS - 1) / CARD_ROWS);
            for (int page = 0; page < pages; page++) {
                if (page > 0) {
                    document.newPage();
                }
                List<CardLine> lines = card.lines().subList(page * CARD_ROWS,
                        Math.min(card.lines().size(), (page + 1) * CARD_ROWS));
                document.add(cardHeader(card));
                document.add(cardTable(lines));
                document.add(cardFooter(page + 1, pages));
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF karty produkcji", e);
        }
        return out.toByteArray();
    }

    private PdfPTable cardHeader(Card card) {
        PdfPTable header = new PdfPTable(new float[] { 3, 4, 3 });
        header.setWidthPercentage(100);
        header.setSpacingAfter(12);

        Phrase stamp = new Phrase();
        stamp.add(new Phrase("Pieczątka zakładu pracy:\n", font(bold, 9, INK)));
        for (String line : card.stamp()) {
            stamp.add(new Phrase(line + "\n", font(bold, 9, INK)));
        }
        PdfPCell stampCell = plain(stamp);
        stampCell.setLeading(0, 1.25f);
        header.addCell(stampCell);

        PdfPCell title = plain(new Phrase("KARTA\nPRODUKCJI", font(bold, 16, INK)));
        title.setHorizontalAlignment(Element.ALIGN_CENTER);
        title.setVerticalAlignment(Element.ALIGN_MIDDLE);
        title.setLeading(0, 1.2f);
        header.addCell(title);

        PdfPCell date = plain(new Phrase("Data: " + DATE.format(card.productionDate()), font(bold, 10, INK)));
        date.setHorizontalAlignment(Element.ALIGN_RIGHT);
        date.setVerticalAlignment(Element.ALIGN_BOTTOM);
        header.addCell(date);
        return header;
    }

    private PdfPTable cardTable(List<CardLine> lines) throws DocumentException {
        PdfPTable table = new PdfPTable(CARD_WIDTHS);
        table.setWidthPercentage(100);
        for (String label : CARD_HEADERS) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font(bold, 5.5f, INK)));
            cell.setBorderColor(RULE);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setPadding(1.5f);
            cell.setPaddingTop(3);
            cell.setPaddingBottom(4);
            cell.setLeading(0, 1.15f);
            table.addCell(cell);
        }
        for (int row = 0; row < CARD_ROWS; row++) {
            CardLine line = row < lines.size() ? lines.get(row) : null;
            if (line == null) {
                for (int column = 0; column < CARD_WIDTHS.length; column++) {
                    table.addCell(cardCell("", column == 0));
                }
                continue;
            }
            table.addCell(cardCell(line.name(), true));
            table.addCell(cardCell(hour(line.startHour()), false));
            table.addCell(cardCell(hour(line.endHour()), false));
            table.addCell(cardCell(degrees(line.bakingTemperature()), false));
            table.addCell(cardCell(degrees(line.internalTemperature()), false));
            table.addCell(cardCell(quantity(line.quantity()), false));
            table.addCell(cardCell(hour(line.endHour()), false));
            table.addCell(cardCell(line.coolingTime() != null ? line.coolingTime().toString() : "-", false));
            table.addCell(cardCell(degrees(line.coolingTemperature()), false));
            table.addCell(cardCell(line.expiryDate() != null ? DATE.format(line.expiryDate()) : "-", false));
            table.addCell(cardCell("-", false));
        }
        return table;
    }

    private PdfPCell cardCell(String text, boolean name) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(regular, 7.5f, INK)));
        cell.setBorderColor(RULE);
        cell.setMinimumHeight(20);
        cell.setPadding(2);
        cell.setHorizontalAlignment(name ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPTable cardFooter(int page, int pages) {
        PdfPTable footer = new PdfPTable(new float[] { 2, 3, 5 });
        footer.setWidthPercentage(100);
        footer.setSpacingBefore(8);
        footer.setKeepTogether(true);
        Font small = font(bold, 8, INK);
        footer.addCell(wide(new Phrase("Rodzaj dokumentu: Załącznik GMP", small), 3));
        footer.addCell(wide(new Phrase("Nazwa dokumentu: Karta produkcji z czasem schładzania CCP1", small), 3));
        footer.addCell(plain(new Phrase("Strona: " + page + "/" + pages, font(regular, 8, INK))));
        footer.addCell(plain(new Phrase("Data wydania: 2026-11-01", font(regular, 8, INK))));
        footer.addCell(plain(new Phrase("Opracowanie: ITEntertaiment", font(regular, 8, INK))));
        PdfPCell signature = wide(new Phrase("Podpis: .........................................", font(bold, 10, INK)),
                3);
        signature.setHorizontalAlignment(Element.ALIGN_RIGHT);
        signature.setPaddingTop(22);
        footer.addCell(signature);
        return footer;
    }

    public byte[] form(Form form) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 28, 28, 28, 44);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED),
                    "Formularz karty produkcji · " + form.storeLabel() + " · wydruk "
                            + PRINTED.format(LocalDate.now())));
            document.addTitle("Formularz karty produkcji");
            document.open();
            int perPage = FORM_ROWS * 2;
            int pages = Math.max(1, (form.lines().size() + perPage - 1) / perPage);
            for (int page = 0; page < pages; page++) {
                if (page > 0) {
                    document.newPage();
                }
                document.add(formHeader(form));
                int from = page * perPage;
                List<FormLine> left = slice(form.lines(), from, FORM_ROWS);
                List<FormLine> right = slice(form.lines(), from + FORM_ROWS, FORM_ROWS);
                PdfPTable columns = new PdfPTable(new float[] { 1, 0.05f, 1 });
                columns.setWidthPercentage(100);
                columns.addCell(nested(formColumn(writer.getDirectContent(), left)));
                columns.addCell(plain(new Phrase(" ")));
                columns.addCell(
                        nested(right.isEmpty() ? new PdfPTable(1) : formColumn(writer.getDirectContent(), right)));
                document.add(columns);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF formularza", e);
        }
        return out.toByteArray();
    }

    private PdfPTable formHeader(Form form) {
        PdfPTable header = new PdfPTable(new float[] { 3, 2 });
        header.setWidthPercentage(100);
        header.setSpacingAfter(10);
        Phrase title = new Phrase();
        title.add(new Phrase("Karta produkcji - formularz\n", font(bold, 14, INK)));
        title.add(new Phrase(form.storeLabel() + " · wpisz ilość przy wyprodukowanych artykułach",
                font(regular, 8, MUTED)));
        PdfPCell titleCell = plain(title);
        titleCell.setLeading(0, 1.3f);
        header.addCell(titleCell);
        PdfPCell date = plain(new Phrase("Data: ............................", font(bold, 10, INK)));
        date.setHorizontalAlignment(Element.ALIGN_RIGHT);
        date.setVerticalAlignment(Element.ALIGN_BOTTOM);
        header.addCell(date);
        return header;
    }

    private PdfPTable formColumn(PdfContentByte canvas, List<FormLine> lines) {
        PdfPTable table = new PdfPTable(new float[] { 120, 40, 102 });
        table.setWidthPercentage(100);
        for (String label : List.of("Nazwa", "Ilość", "Kod")) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font(bold, 7.5f, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorderColor(LIGHT_RULE);
            cell.setPadding(4);
            cell.setHorizontalAlignment(label.equals("Nazwa") ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        for (FormLine line : lines) {
            PdfPCell name = new PdfPCell(new Phrase(line.name(), font(regular, 7.5f, INK)));
            formBodyCell(name);
            table.addCell(name);
            // Puste pole: pracownik wpisuje długopisem, ile wyprodukował.
            PdfPCell quantity = new PdfPCell(new Phrase(" "));
            formBodyCell(quantity);
            table.addCell(quantity);
            PdfPCell code = line.code() != null
                    ? new PdfPCell(PdfBarcodes.image(canvas, line.code(), 1f, 98, 28), false)
                    : new PdfPCell(new Phrase("bez kodu", font(regular, 7, MUTED)));
            formBodyCell(code);
            code.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(code);
        }
        return table;
    }

    private static void formBodyCell(PdfPCell cell) {
        cell.setBorderColor(LIGHT_RULE);
        cell.setPadding(3);
        cell.setFixedHeight(34);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
    }

    private static List<FormLine> slice(List<FormLine> lines, int from, int size) {
        return from >= lines.size() ? List.of() : lines.subList(from, Math.min(lines.size(), from + size));
    }

    private static PdfPCell nested(PdfPTable table) {
        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell plain(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingBottom(2);
        return cell;
    }

    private static PdfPCell wide(Phrase phrase, int colspan) {
        PdfPCell cell = plain(phrase);
        cell.setColspan(colspan);
        return cell;
    }

    private static String hour(int hour) {
        return hour + ":00";
    }

    private static String degrees(Integer value) {
        return value != null ? value + "°C" : "-";
    }

    private static String quantity(BigDecimal value) {
        return new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(POLISH)).format(value);
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
