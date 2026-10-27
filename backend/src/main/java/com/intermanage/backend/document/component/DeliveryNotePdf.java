package com.intermanage.backend.document.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.supplier.entity.Supplier;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Wydruk bonu dostawy przyjęcia PZ, w układzie bonu z systemu sklepu: dostawca, linie z ceną zakupu i sprzedaży,
 * sumy z marżą, wycena w cenach zakupu i sprzedaży według stawek VAT i według rejonów nomenklatury. Linie i sumy są
 * po przypiętych korektach ({@link CorrectedDelivery}); zmienione linie mają gwiazdkę i wartości sprzed korekty,
 * a na końcu jest lista uwzględnionych korekt.
 */
@Component
public class DeliveryNotePdf {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private final BaseFont regular;
    private final BaseFont bold;

    public DeliveryNotePdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /**
     * Dane wydruku: przyjęcie (nagłówek), sklep, dostawca z listy dostawców ({@code null}, gdy go tam nie ma),
     * przyjęcie po korektach, dokumenty korekt, nazwy rejonów nomenklatury po kodzie i kto drukuje.
     */
    public record Note(Document delivery, Store store, Supplier supplier, CorrectedDelivery corrected,
            List<Document> corrections, Map<String, String> regions, String printedBy) {
    }

    public byte[] pdf(Note note) {
        String bon = note.delivery().bonNumber();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4.rotate(), 30, 30, 30, 48);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageFooter(font(regular, 7.5f, MUTED), "Bon dostawy nr " + bon + " · sklep "
                    + note.store().getNumber() + " · wydruk " + DATE_TIME.format(ZonedDateTime.now(STORE_ZONE))));
            document.addTitle("Bon dostawy nr " + bon);
            document.addCreator("Intermanage");
            document.open();

            writeHeader(document, note);
            writeTitle(document, note, bon);
            document.add(linesTable(note.corrected().lines()));
            writeTotals(document, note.corrected().lines());
            writeValuations(document, note.corrected().lines());
            writeRegions(document, note);
            writeCorrections(document, note.corrections());
            writeSignatures(document, note.printedBy());
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku bonu dostawy", e);
        }
        return out.toByteArray();
    }

    /** Daty i numery przyjęcia po lewej, dostawca po prawej. */
    private void writeHeader(com.lowagie.text.Document document, Note note) throws DocumentException {
        Document delivery = note.delivery();
        PdfPTable header = new PdfPTable(new float[] {58, 42});
        header.setWidthPercentage(100);

        PdfPTable info = new PdfPTable(2);
        info.addCell(infoCell("Data przyjęcia", DATE.format(delivery.getDocumentDate())));
        info.addCell(infoCell("Nr dokumentu dostawcy", delivery.getSupplierDocumentNumber()));
        info.addCell(infoCell("Nr wewnętrzny", delivery.getNumber()));
        Store store = note.store();
        info.addCell(infoCell("Sklep", store.getNumber() + " · " + store.getName()));
        PdfPCell infoCell = new PdfPCell(info);
        infoCell.setBorder(Rectangle.NO_BORDER);
        infoCell.setPadding(0);
        infoCell.setPaddingRight(24);
        header.addCell(infoCell);

        Supplier supplier = note.supplier();
        Phrase party = new Phrase();
        party.add(new Phrase("DOSTAWCA\n", font(bold, 7, MUTED)));
        party.add(new Phrase(Objects.toString(supplier != null ? supplier.getName() : delivery.getSupplierName(), "–")
                + "\n", font(bold, 10, INK)));
        if (supplier != null) {
            if (supplier.getAddress() != null) {
                party.add(new Phrase(supplier.getAddress() + "\n", font(regular, 8.5f, INK)));
            }
            String city = String.join(" ", List.of(Objects.toString(supplier.getPostalCode(), ""),
                    Objects.toString(supplier.getCity(), ""))).strip();
            if (!city.isEmpty()) {
                party.add(new Phrase(city + "\n", font(regular, 8.5f, INK)));
            }
            if (supplier.getNip() != null) {
                party.add(new Phrase("NIP: " + supplier.getNip() + "\n", font(regular, 8.5f, INK)));
            }
        }
        if (delivery.getSupplierCode() != null) {
            party.add(new Phrase("Kod dostawcy: " + delivery.getSupplierCode(), font(regular, 8.5f, INK)));
        }
        PdfPCell partyCell = cell(party);
        partyCell.setBorder(Rectangle.TOP);
        partyCell.setBorderColor(RULE);
        partyCell.setPaddingTop(6);
        partyCell.setLeading(0, 1.35f);
        header.addCell(partyCell);
        document.add(header);
    }

    private void writeTitle(com.lowagie.text.Document document, Note note, String bon) throws DocumentException {
        PdfPTable title = new PdfPTable(1);
        title.setWidthPercentage(100);
        title.setSpacingBefore(16);
        title.setSpacingAfter(10);
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("BON DOSTAWY NR " + bon, font(bold, 16, INK)));
        if (!note.corrections().isEmpty()) {
            phrase.add(new Phrase("\nWartości po korektach: " + note.corrections().stream()
                    .map(correction -> correction.getType().singular().toLowerCase(POLISH) + " "
                            + correction.getNumber())
                    .collect(Collectors.joining(", ")), font(regular, 8, MUTED)));
        }
        PdfPCell cell = cell(phrase);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setLeading(0, 1.4f);
        title.addCell(cell);
        document.add(title);
    }

    private PdfPTable linesTable(List<CorrectedLine> lines) {
        List<Column> columns = List.of(
                new Column("Lp.", 3.5f, Element.ALIGN_RIGHT, line -> String.valueOf(line.lineNumber())),
                new Column("Indeks ITM8", 9.5f, Element.ALIGN_LEFT, CorrectedLine::itm8),
                new Column("EAN", 9.5f, Element.ALIGN_LEFT, line -> Objects.toString(line.ean(), "")),
                new Column("Nazwa artykułu", 31, Element.ALIGN_LEFT, null),
                new Column("VAT", 5, Element.ALIGN_RIGHT, line -> line.vatRate() != null ? line.vatRate() + "%" : ""),
                new Column("Ilość doręcz.", 8, Element.ALIGN_RIGHT, line -> quantity(line.quantity())),
                new Column("Cena zak. jedn.", 8, Element.ALIGN_RIGHT, line -> price(line.purchasePrice())),
                new Column("Cena sprz. jedn.", 8, Element.ALIGN_RIGHT, line -> price(line.salePriceDocument())),
                new Column("Wartość zak. netto", 10, Element.ALIGN_RIGHT, line -> money(line.purchaseValue())),
                new Column("Marża %", 7.5f, Element.ALIGN_RIGHT, line -> percent(line.marginPercent())));
        float[] widths = new float[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).width();
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (Column column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(4);
            cell.setHorizontalAlignment(column.alignment());
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(cell);
        }
        for (CorrectedLine line : lines) {
            for (Column column : columns) {
                Phrase phrase;
                if (column.value() == null) {
                    phrase = new Phrase();
                    phrase.add(new Phrase((line.corrected() ? "* " : "") + Objects.toString(line.name(), "–"),
                            font(line.corrected() ? bold : regular, 8, INK)));
                    if (line.corrected()) {
                        phrase.add(new Phrase("\n" + (line.originalQuantity() == null ? "dopisane korektą"
                                : "przed korektą: ilość " + quantity(line.originalQuantity()) + ", wartość "
                                        + money(line.originalPurchaseValue())), font(regular, 6.5f, MUTED)));
                    }
                } else {
                    phrase = new Phrase(column.value().apply(line), font(regular, 8, INK));
                }
                PdfPCell cell = new PdfPCell(phrase);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(RULE);
                cell.setPadding(3.5f);
                cell.setPaddingBottom(4.5f);
                cell.setLeading(0, 1.2f);
                cell.setHorizontalAlignment(column.alignment());
                table.addCell(cell);
            }
        }
        return table;
    }

    private record Column(String label, float width, int alignment, Function<CorrectedLine, String> value) {
    }

    /** Sumy w cenach zakupu i marża (od sprzedaży brutto, jak w raporcie i na bonie z systemu sklepu). */
    private void writeTotals(com.lowagie.text.Document document, List<CorrectedLine> lines) throws DocumentException {
        Valuation purchase = purchaseValuation(lines).total();
        Valuation sale = saleValuation(lines).total();
        BigDecimal margin = sale.net().subtract(purchase.net());

        PdfPTable layout = new PdfPTable(new float[] {60, 40});
        layout.setWidthPercentage(100);
        layout.setSpacingBefore(10);
        layout.setKeepTogether(true);
        Phrase count = new Phrase();
        count.add(new Phrase("Pozycji: " + lines.size(), font(regular, 8, MUTED)));
        if (lines.stream().anyMatch(CorrectedLine::corrected)) {
            count.add(new Phrase("\n* pozycja zmieniona albo dopisana przez korektę", font(regular, 7, MUTED)));
        }
        PdfPCell countCell = cell(count);
        countCell.setLeading(0, 1.4f);
        layout.addCell(countCell);

        PdfPTable totals = new PdfPTable(new float[] {62, 38});
        totalsRow(totals, "Suma zakupu netto", money(purchase.net()), false);
        totalsRow(totals, "Suma VAT", money(purchase.vat()), false);
        totalsRow(totals, "Suma zakupu brutto", money(purchase.gross()), true);
        totalsRow(totals, "Marża", money(margin), false);
        totalsRow(totals, "Marża (%)", percent(sale.gross().signum() == 0 ? null
                : margin.multiply(HUNDRED).divide(sale.gross(), 2, RoundingMode.HALF_UP)), false);
        PdfPCell totalsCell = new PdfPCell(totals);
        totalsCell.setBorder(Rectangle.NO_BORDER);
        totalsCell.setPadding(0);
        layout.addCell(totalsCell);
        document.add(layout);
    }

    private void totalsRow(PdfPTable table, String label, String value, boolean emphasized) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font(emphasized ? bold : regular, 8.5f, INK)));
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font(emphasized ? bold : regular, 8.5f, INK)));
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell cell : List.of(labelCell, valueCell)) {
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            cell.setPadding(4);
            table.addCell(cell);
        }
    }

    /** Wycena w cenach zakupu i w cenach sprzedaży według stawek VAT, obok siebie. */
    private void writeValuations(com.lowagie.text.Document document, List<CorrectedLine> lines)
            throws DocumentException {
        PdfPTable layout = new PdfPTable(new float[] {49, 2, 49});
        layout.setWidthPercentage(100);
        layout.setSpacingBefore(18);
        layout.setKeepTogether(true);
        layout.addCell(nested(valuationTable("Wycena w cenach zakupu", purchaseValuation(lines))));
        layout.addCell(cell(new Phrase(" ")));
        layout.addCell(nested(valuationTable("Wycena w cenach sprzedaży", saleValuation(lines))));
        document.add(layout);
    }

    private PdfPTable valuationTable(String title, Valuations valuations) {
        PdfPTable table = sectionTable(title, new float[] {28, 24, 24, 24});
        header(table, List.of("Stawka VAT", "Wartość netto", "VAT", "Wartość brutto"), 1);
        valuations.byRate().forEach((rate, valuation) -> valuationRow(table,
                rate == null ? "–" : rate + "%", valuation, false));
        valuationRow(table, "Suma", valuations.total(), true);
        return table;
    }

    /** Wycena w cenach zakupu według rejonów nomenklatury (pierwsze dwie cyfry kodu artykułu). */
    private void writeRegions(com.lowagie.text.Document document, Note note) throws DocumentException {
        Map<String, List<CorrectedLine>> byRegion = new TreeMap<>();
        for (CorrectedLine line : note.corrected().lines()) {
            String code = line.nomenclatureCode() != null && line.nomenclatureCode().length() >= 2
                    ? line.nomenclatureCode().substring(0, 2) : "";
            byRegion.computeIfAbsent(code, key -> new ArrayList<>()).add(line);
        }
        PdfPTable table = sectionTable("Wycena w cenach zakupu według rejonów", new float[] {10, 42, 16, 16, 16});
        table.setWidthPercentage(70);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingBefore(16);
        table.setKeepTogether(true);
        header(table, List.of("Kod", "Rejon", "Wartość netto", "VAT", "Wartość brutto"), 2);
        byRegion.forEach((code, lines) -> {
            Valuation valuation = purchaseValuation(lines).total();
            row(table, List.of(code.isEmpty() ? "–" : code,
                    code.isEmpty() ? "Bez nomenklatury" : note.regions().getOrDefault(code, "Rejon " + code),
                    money(valuation.net()), money(valuation.vat()), money(valuation.gross())), 2, false);
        });
        Valuation total = purchaseValuation(note.corrected().lines()).total();
        row(table, List.of("Suma", "", money(total.net()), money(total.vat()), money(total.gross())), 2, true);
        document.add(table);
    }

    private void writeCorrections(com.lowagie.text.Document document, List<Document> corrections)
            throws DocumentException {
        if (corrections.isEmpty()) {
            return;
        }
        PdfPTable table = sectionTable("Uwzględnione korekty", new float[] {24, 22, 12, 10, 16, 16});
        table.setSpacingBefore(16);
        table.setKeepTogether(true);
        header(table, List.of("Rodzaj", "Numer", "Data", "Pozycji", "Zakup netto", "Sprzedaż brutto"), 3);
        for (Document correction : corrections) {
            row(table, List.of(correction.getType().singular(), correction.getNumber(),
                    DATE.format(correction.getDocumentDate()), String.valueOf(correction.getLineCount()),
                    money(correction.getPurchaseValue()), money(correction.getSaleValueGross())), 3, false);
        }
        document.add(table);
    }

    private void writeSignatures(com.lowagie.text.Document document, String printedBy) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {40, 20, 40});
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        table.setKeepTogether(true);
        table.addCell(signature("Wydrukował(a)", printedBy));
        table.addCell(cell(new Phrase(" ")));
        table.addCell(signature("Zatwierdził(a)", null));
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
        return nested(table);
    }

    /** VAT w cenach zakupu liczony od sumy netto w stawce, zaokrąglony do grosza (jak na bonie z systemu sklepu). */
    private static Valuations purchaseValuation(List<CorrectedLine> lines) {
        Map<Short, Valuation> byRate = new LinkedHashMap<>();
        rates(lines).forEach((rate, rateLines) -> {
            BigDecimal net = total(rateLines, CorrectedLine::purchaseValue);
            BigDecimal vat = rate == null ? BigDecimal.ZERO
                    : net.multiply(BigDecimal.valueOf(rate)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            byRate.put(rate, new Valuation(net, vat, net.add(vat)));
        });
        return new Valuations(byRate);
    }

    /** W cenach sprzedaży kwoty są w liniach raportu (netto, VAT, brutto). */
    private static Valuations saleValuation(List<CorrectedLine> lines) {
        Map<Short, Valuation> byRate = new LinkedHashMap<>();
        rates(lines).forEach((rate, rateLines) -> byRate.put(rate, new Valuation(
                total(rateLines, CorrectedLine::saleValueNet), total(rateLines, CorrectedLine::vatValue),
                total(rateLines, CorrectedLine::saleValueGross))));
        return new Valuations(byRate);
    }

    /** Linie według stawki VAT, od najwyższej; bez stawki na końcu. */
    private static Map<Short, List<CorrectedLine>> rates(List<CorrectedLine> lines) {
        Map<Short, List<CorrectedLine>> byRate = new TreeMap<>(Comparator.nullsLast(Comparator.<Short>reverseOrder()));
        lines.forEach(line -> byRate.computeIfAbsent(line.vatRate(), rate -> new ArrayList<>()).add(line));
        return byRate;
    }

    private static BigDecimal total(List<CorrectedLine> lines, Function<CorrectedLine, BigDecimal> value) {
        return lines.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Valuation(BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }

    private record Valuations(Map<Short, Valuation> byRate) {

        Valuation total() {
            return byRate.values().stream().reduce(new Valuation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                    (a, b) -> new Valuation(a.net().add(b.net()), a.vat().add(b.vat()), a.gross().add(b.gross())));
        }
    }

    private void valuationRow(PdfPTable table, String label, Valuation valuation, boolean emphasized) {
        row(table, List.of(label, money(valuation.net()), money(valuation.vat()), money(valuation.gross())), 1,
                emphasized);
    }

    /** Tabela z tytułem w pierwszym wierszu nagłówka, żeby tytuł nie został sam na dole strony. */
    private PdfPTable sectionTable(String title, float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(2);
        PdfPCell titleCell = new PdfPCell(new Phrase(title, font(bold, 9, INK)));
        titleCell.setColspan(widths.length);
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPadding(0);
        titleCell.setPaddingBottom(5);
        table.addCell(titleCell);
        return table;
    }

    /** Nagłówek kolumn; od kolumny {@code firstNumber} są liczby, wyrównane do prawej. */
    private void header(PdfPTable table, List<String> labels, int firstNumber) {
        for (int i = 0; i < labels.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(labels.get(i), font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(4);
            cell.setHorizontalAlignment(i >= firstNumber ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(cell);
        }
    }

    private void row(PdfPTable table, List<String> values, int firstNumber, boolean emphasized) {
        for (int i = 0; i < values.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(values.get(i), font(emphasized ? bold : regular, 8, INK)));
            cell.setBorder(emphasized ? Rectangle.TOP : Rectangle.BOTTOM);
            cell.setBorderColor(emphasized ? MUTED : RULE);
            cell.setPadding(4);
            cell.setHorizontalAlignment(i >= firstNumber ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(cell);
        }
    }

    private PdfPCell infoCell(String label, String value) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label + "\n", font(regular, 7, MUTED)));
        phrase.add(new Phrase(value != null ? value : "–", font(bold, 9, INK)));
        PdfPCell cell = cell(phrase);
        cell.setBackgroundColor(BAND);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(Color.WHITE);
        cell.setBorderWidth(2);
        cell.setPadding(7);
        cell.setPaddingTop(5);
        cell.setLeading(0, 1.35f);
        return cell;
    }

    private static PdfPCell nested(PdfPTable table) {
        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
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
        return value == null ? "" : format("0.00", value);
    }

    private static String format(String pattern, BigDecimal value) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(POLISH)).format(value);
    }

    /** Stopka: bon i data wydruku po lewej, "Strona X z Y" po prawej (liczba stron dopisywana na końcu). */
    private static final class PageFooter extends PdfPageEventHelper {

        private static final float TOTAL_WIDTH = 24;

        private final Font font;
        private final String text;
        private PdfTemplate total;
        private int pages;

        PageFooter(Font font, String text) {
            this.font = font;
            this.text = text;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, com.lowagie.text.Document document) {
            total = writer.getDirectContent().createTemplate(TOTAL_WIDTH, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, com.lowagie.text.Document document) {
            pages = writer.getPageNumber();
            PdfContentByte canvas = writer.getDirectContent();
            float y = document.bottom() - 22;
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase(text, font), document.left(), y, 0);
            float right = document.right() - TOTAL_WIDTH;
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Strona " + pages + " z", font),
                    right, y, 0);
            canvas.addTemplate(total, right + 3, y - 3);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, com.lowagie.text.Document document) {
            ColumnText.showTextAligned(total, Element.ALIGN_LEFT, new Phrase(String.valueOf(pages), font), 0, 3, 0);
        }
    }
}
