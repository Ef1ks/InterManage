package com.intermanage.backend.document.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.document.component.DocumentPrint.Detail;
import com.intermanage.backend.document.component.DocumentPrint.Heading;
import com.intermanage.backend.document.component.DocumentPrint.Line;
import com.intermanage.backend.document.component.DocumentPrint.NomenclatureRow;
import com.intermanage.backend.document.component.DocumentPrint.Party;
import com.intermanage.backend.document.component.DocumentPrint.Totals;
import com.intermanage.backend.document.component.DocumentPrint.VatRow;
import com.lowagie.text.Document;
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
 * Wydruk dokumentu magazynowego (likwidacja, ruch międzymagazynowy, wydania, zwroty, korekty), A4 pionowo do segregatora:
 * tytuł i sklep, kafelki z najważniejszymi liczbami, linie (nazwa z kodami pod spodem), rozbicie na nomenklaturę, stawki
 * VAT i podpisy. Tabele powtarzają nagłówek na każdej stronie, a wiersze nie dzielą się między strony.
 */
@Component
public class DocumentPrintPdf {

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);

    /** Tyle wierszy tabela może przenieść w całości na następną stronę; dłuższa po prostu się dzieli. */
    private static final int KEEP_TOGETHER_ROWS = 24;

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private final BaseFont regular;
    private final BaseFont bold;

    public DocumentPrintPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public byte[] pdf(DocumentPrint print) {
        Heading heading = print.heading();
        String name = heading.label() + " nr " + heading.number();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 32, 32, 32, 48);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7.5f, MUTED), name + " · sklep "
                    + heading.store().name() + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(StoreTime.ZONE))));
            document.addTitle(name);
            document.addCreator("Intermanage");
            document.open();

            writeTitle(document, heading);
            writeTiles(document, print);
            writeParty(document, heading.party());
            document.add(linesTable(print));
            writeNomenclature(document, print);
            writeVatRates(document, print);
            writeSignatures(document, print);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku dokumentu", e);
        }
        return out.toByteArray();
    }

    /** Tytuł z numerem po lewej, sklep po prawej, pod spodem gruba linia. */
    private void writeTitle(Document document, Heading heading) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {62, 38});
        table.setWidthPercentage(100);

        Phrase title = new Phrase();
        title.add(new Phrase(heading.title() + "\n", font(bold, 17, INK)));
        title.add(new Phrase("nr " + heading.number(), font(regular, 10.5f, INK)));
        PdfPCell titleCell = cell(title);
        titleCell.setLeading(0, 1.3f);
        titleCell.setPaddingBottom(9);
        titleCell.setBorder(Rectangle.BOTTOM);
        titleCell.setBorderWidth(1.5f);
        titleCell.setBorderColor(INK);
        table.addCell(titleCell);

        Party store = heading.store();
        Phrase storePhrase = new Phrase();
        storePhrase.add(new Phrase(store.name(), font(bold, 9, INK)));
        store.lines().forEach(line -> storePhrase.add(new Phrase("\n" + line, font(regular, 8, MUTED))));
        PdfPCell storeCell = cell(storePhrase);
        storeCell.setLeading(0, 1.3f);
        storeCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        storeCell.setPaddingBottom(9);
        storeCell.setBorder(Rectangle.BOTTOM);
        storeCell.setBorderWidth(1.5f);
        storeCell.setBorderColor(INK);
        table.addCell(storeCell);
        document.add(table);
    }

    /** Data, liczba pozycji, dodatkowe pola dokumentu i obie sumy: to, po co najczęściej sięga się do wydruku. */
    private void writeTiles(Document document, DocumentPrint print) throws DocumentException {
        Heading heading = print.heading();
        List<Detail> tiles = new ArrayList<>();
        tiles.add(new Detail("Data dokumentu", DATE.format(heading.date())));
        tiles.add(new Detail("Pozycji", String.valueOf(print.lines().size())));
        tiles.addAll(heading.details());
        tiles.add(new Detail("Zakup netto", money(print.totals().purchaseValue())));
        tiles.add(new Detail("Sprzedaż brutto", money(print.totals().saleValueGross())));

        PdfPTable table = new PdfPTable(tiles.size());
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        for (Detail tile : tiles) {
            Phrase phrase = new Phrase();
            phrase.add(new Phrase(tile.label() + "\n", font(regular, 7, MUTED)));
            phrase.add(new Phrase(Objects.toString(tile.value(), "–"), font(bold, 10, INK)));
            PdfPCell cell = cell(phrase);
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.BOX);
            cell.setBorderColor(Color.WHITE);
            cell.setBorderWidth(2);
            cell.setPadding(7);
            cell.setPaddingTop(5);
            cell.setLeading(0, 1.35f);
            table.addCell(cell);
        }
        document.add(table);
    }

    /** Druga strona dokumentu; bez nazwy zostaje puste miejsce do wpisania ręcznie. */
    private void writeParty(Document document, Party party) throws DocumentException {
        if (party == null) {
            return;
        }
        PdfPTable table = new PdfPTable(new float[] {16, 84});
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        PdfPCell label = cell(new Phrase(party.label(), font(regular, 8, MUTED)));
        label.setPaddingTop(1);
        table.addCell(label);
        Phrase phrase = new Phrase();
        if (party.name() != null) {
            phrase.add(new Phrase(party.name(), font(bold, 9.5f, INK)));
            if (!party.lines().isEmpty()) {
                phrase.add(new Phrase("\n" + String.join(" · ", party.lines()), font(regular, 8, MUTED)));
            }
        }
        PdfPCell value = cell(phrase);
        value.setLeading(0, 1.3f);
        if (party.name() == null) {
            value.setBorder(Rectangle.BOTTOM);
            value.setBorderColor(MUTED);
            value.setMinimumHeight(22);
        }
        table.addCell(value);
        document.add(table);
    }

    private PdfPTable linesTable(DocumentPrint print) {
        PdfPTable table = new PdfPTable(new float[] {4.5f, 37, 8, 5, 9.5f, 11, 9.5f, 11.5f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        table.setSplitRows(false);
        header(table, List.of("Lp.", "Artykuł", "Ilość", "VAT", "Cena zakupu", "Zakup netto", "Cena sprz.",
                "Sprzedaż brutto"), Stream.of(0, 2, 3, 4, 5, 6, 7).toList());
        boolean perLineSupplier = print.heading().party() != null && print.heading().party().name() == null;
        for (Line line : print.lines()) {
            lineCell(table, String.valueOf(line.lineNumber()), Element.ALIGN_RIGHT, MUTED);
            table.addCell(articleCell(line, perLineSupplier));
            String quantity = quantity(line.quantity());
            lineCell(table, line.unit() != null ? quantity + " " + line.unit() : quantity, Element.ALIGN_RIGHT, INK);
            lineCell(table, line.vatRate() != null ? line.vatRate() + "%" : "", Element.ALIGN_RIGHT, INK);
            lineCell(table, price(line.purchasePrice()), Element.ALIGN_RIGHT, INK);
            lineCell(table, money(line.purchaseValue()), Element.ALIGN_RIGHT, INK);
            lineCell(table, price(line.salePrice()), Element.ALIGN_RIGHT, INK);
            lineCell(table, money(line.saleValueGross()), Element.ALIGN_RIGHT, INK);
        }
        Totals totals = print.totals();
        PdfPCell label = totalCell(print.skippedLines() > 0
                ? "Razem (pominięto pozycje z ilością 0: " + print.skippedLines() + ")" : "Razem", Element.ALIGN_LEFT);
        label.setColspan(5);
        table.addCell(label);
        table.addCell(totalCell(money(totals.purchaseValue()), Element.ALIGN_RIGHT));
        table.addCell(totalCell("", Element.ALIGN_RIGHT));
        table.addCell(totalCell(money(totals.saleValueGross()), Element.ALIGN_RIGHT));
        return table;
    }

    private PdfPCell articleCell(Line line, boolean perLineSupplier) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(Objects.toString(line.name(), "Bez nazwy"), font(regular, 8, INK)));
        String codes = Stream.of(line.ean(), line.itm8(), line.brand(), perLineSupplier ? line.supplier() : null)
                .filter(Objects::nonNull).collect(Collectors.joining(" · "));
        phrase.add(new Phrase("\n" + codes, font(regular, 6.5f, MUTED)));
        if (line.note() != null) {
            phrase.add(new Phrase("\n" + line.note(), font(regular, 6.5f, MUTED)));
        }
        PdfPCell cell = new PdfPCell(phrase);
        bodyCell(cell);
        return cell;
    }

    private void lineCell(PdfPTable table, String text, int alignment, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(regular, 8, color)));
        bodyCell(cell);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    private static void bodyCell(PdfPCell cell) {
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setPadding(2.5f);
        cell.setPaddingBottom(3.5f);
        cell.setLeading(0, 1.15f);
    }

    private PdfPCell totalCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(bold, 8.5f, INK)));
        cell.setBorder(Rectangle.TOP);
        cell.setBorderWidth(1);
        cell.setBorderColor(INK);
        cell.setPadding(4);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    /** Rejony pogrubione, pod każdym jego rodziny z wcięciem. */
    private void writeNomenclature(Document document, DocumentPrint print) throws DocumentException {
        List<NomenclatureRow> rows = print.nomenclature();
        boolean shares = rows.stream().anyMatch(row -> row.share() != null);
        PdfPTable table = sectionTable("Podsumowanie według nomenklatury",
                shares ? new float[] {7, 33, 7, 14, 14, 15, 10} : new float[] {8, 38, 8, 15, 15, 16}, rows.size());
        List<String> labels = new ArrayList<>(List.of("Kod", "Nomenklatura", "Pozycji", "Zakup netto",
                "Sprzedaż netto", "Sprzedaż brutto"));
        if (shares) {
            labels.add("Udział");
        }
        header(table, labels, Stream.iterate(2, i -> i < labels.size(), i -> i + 1).toList());
        for (NomenclatureRow row : rows) {
            Font font = font(row.region() ? bold : regular, 8, INK);
            List<String> values = new ArrayList<>(List.of(row.code().isEmpty() ? "–" : row.code(), row.name(),
                    String.valueOf(row.lineCount()), money(row.purchaseValue()), money(row.saleValueNet()),
                    money(row.saleValueGross())));
            if (shares) {
                values.add(row.share() != null ? percent(row.share()) + "%" : "");
            }
            for (int i = 0; i < values.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(values.get(i), font));
                cell.setBorder(row.region() ? Rectangle.TOP : Rectangle.NO_BORDER);
                cell.setBorderColor(RULE);
                cell.setPadding(row.region() ? 4 : 2.5f);
                cell.setPaddingLeft(i == 1 && !row.region() ? 12 : 4);
                cell.setPaddingRight(4);
                cell.setHorizontalAlignment(i >= 2 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(cell);
            }
        }
        Totals totals = print.totals();
        PdfPCell label = totalCell("Razem", Element.ALIGN_LEFT);
        label.setColspan(2);
        table.addCell(label);
        table.addCell(totalCell(String.valueOf(print.lines().size()), Element.ALIGN_RIGHT));
        table.addCell(totalCell(money(totals.purchaseValue()), Element.ALIGN_RIGHT));
        table.addCell(totalCell(money(totals.saleValueNet()), Element.ALIGN_RIGHT));
        table.addCell(totalCell(money(totals.saleValueGross()), Element.ALIGN_RIGHT));
        if (shares) {
            table.addCell(totalCell("100,00%", Element.ALIGN_RIGHT));
        }
        document.add(table);
    }

    private void writeVatRates(Document document, DocumentPrint print) throws DocumentException {
        PdfPTable table = sectionTable("Podsumowanie według stawek VAT", new float[] {16, 14, 14, 14, 14, 14, 14},
                print.vatRates().size());
        header(table, List.of("Stawka VAT", "Zakup netto", "VAT od zakupu", "Zakup brutto", "Sprzedaż netto",
                "VAT od sprzedaży", "Sprzedaż brutto"), List.of(1, 2, 3, 4, 5, 6));
        for (VatRow row : print.vatRates()) {
            List<String> values = List.of(row.rate() != null ? row.rate() + "%" : "–", money(row.purchaseNet()),
                    money(row.purchaseVat()), money(row.purchaseNet().add(row.purchaseVat())), money(row.saleNet()),
                    money(row.saleVat()), money(row.saleGross()));
            for (int i = 0; i < values.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(values.get(i), font(regular, 8, INK)));
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(RULE);
                cell.setPadding(4);
                cell.setHorizontalAlignment(i > 0 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(cell);
            }
        }
        Totals totals = print.totals();
        table.addCell(totalCell("Razem", Element.ALIGN_LEFT));
        Stream.of(totals.purchaseValue(), totals.purchaseVat(), totals.purchaseValue().add(totals.purchaseVat()),
                totals.saleValueNet(), totals.saleVat(), totals.saleValueGross())
                .forEach(value -> table.addCell(totalCell(money(value), Element.ALIGN_RIGHT)));
        document.add(table);
    }

    /** Podpisy obok siebie; pod pierwszym nazwisko osoby, która drukuje. */
    private void writeSignatures(Document document, DocumentPrint print) throws DocumentException {
        List<String> labels = print.heading().signatures();
        PdfPTable table = new PdfPTable(labels.size());
        table.setWidthPercentage(100);
        table.setSpacingBefore(34);
        table.setKeepTogether(true);
        for (int i = 0; i < labels.size(); i++) {
            table.addCell(signature(labels.get(i), i == 0 ? print.printedBy() : null));
        }
        document.add(table);
    }

    /** Miejsce na podpis: nazwisko (albo puste) nad linią, opis pod nią. */
    private PdfPCell signature(String label, String name) {
        PdfPTable table = new PdfPTable(1);
        PdfPCell line = cell(new Phrase(Objects.toString(name, " "), font(regular, 9, INK)));
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderColor(MUTED);
        line.setMinimumHeight(30);
        line.setPaddingBottom(4);
        line.setVerticalAlignment(Element.ALIGN_BOTTOM);
        line.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(line);
        PdfPCell caption = cell(new Phrase(label, font(regular, 7, MUTED)));
        caption.setPaddingTop(3);
        caption.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(caption);

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingLeft(10);
        cell.setPaddingRight(10);
        return cell;
    }

    /** Tabela z tytułem w pierwszym wierszu nagłówka, żeby tytuł nie został sam na dole strony. */
    private PdfPTable sectionTable(String title, float[] widths, int rows) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingBefore(18);
        table.setHeaderRows(2);
        table.setSplitLate(false);
        table.setSplitRows(false);
        table.setKeepTogether(rows <= KEEP_TOGETHER_ROWS);
        PdfPCell titleCell = new PdfPCell(new Phrase(title, font(bold, 10, INK)));
        titleCell.setColspan(widths.length);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPadding(0);
        titleCell.setPaddingBottom(5);
        table.addCell(titleCell);
        return table;
    }

    /** Nagłówek kolumn; {@code right}: numery kolumn wyrównanych do prawej. */
    private void header(PdfPTable table, List<String> labels, List<Integer> right) {
        for (int i = 0; i < labels.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(labels.get(i), font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(4);
            cell.setHorizontalAlignment(right.contains(i) ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(cell);
        }
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : format("#,##0.00", value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String price(BigDecimal value) {
        return value == null ? "" : format("#,##0.00##", value);
    }

    private static String quantity(BigDecimal value) {
        return value == null ? "" : format("#,##0.###", value);
    }

    private static String percent(BigDecimal value) {
        return format("0.00", value);
    }

    private static String format(String pattern, BigDecimal value) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(POLISH)).format(value);
    }
}
