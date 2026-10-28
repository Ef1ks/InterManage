package com.intermanage.backend.deliorder.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.deliorder.entity.OrderUnit;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.Barcode;
import com.lowagie.text.pdf.BarcodeEAN;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Pliki zamówienia garmażu do pobrania: lista EAN (.lst) dla czytników i kas, arkusz Excel oraz PDF do wydruku
 * z kodami kreskowymi i miejscem na wpisanie długopisem ilości zrealizowanej. Pozycje z ilością 0 są pomijane.
 */
@Component
public class DeliOrderDocuments {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter SUBMITTED = DateTimeFormatter.ofPattern("d.MM.yyyy, HH:mm", POLISH);
    private static final DateTimeFormatter DELIVERY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", POLISH);

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);

    private final XlsxWriter xlsxWriter;
    private final BaseFont regular;
    private final BaseFont bold;

    public DeliOrderDocuments(XlsxWriter xlsxWriter, PdfFonts fonts) {
        this.xlsxWriter = xlsxWriter;
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** Zamówienie w postaci potrzebnej do plików; {@code lines} w kolejności formularza. */
    public record OrderDocument(
            UUID id,
            String storeNumber,
            String storeName,
            String supplierNumber,
            String supplierName,
            Instant submittedAt,
            String submittedBy,
            LocalDate deliveryDate,
            List<Line> lines) {

        /** Pozycje z ilością większą od zera (ilość 0 = nie zamawiamy / nie będzie dostarczone). */
        List<Line> orderedLines() {
            return lines.stream().filter(line -> line.quantity().signum() > 0).toList();
        }
    }

    /** {@code brand} trafia tylko do Excela; na wydruku jest sama nazwa towaru. */
    public record Line(String sectionName, String categoryName, String ean, String name, String brand, OrderUnit unit,
            BigDecimal quantity) {
    }

    /** Same kody EAN, jeden w linii, w kolejności zamówienia. CRLF, bo plik trafia do programów na Windows. */
    public byte[] lst(OrderDocument order) {
        StringBuilder text = new StringBuilder();
        for (Line line : order.orderedLines()) {
            text.append(line.ean()).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Najwięcej, ile mieści pole ilości w pliku PROTO6: 6 cyfr całości i 3 po przecinku. */
    private static final BigDecimal PROTO_MAX = new BigDecimal("999999.999");

    /**
     * Plik PROTO6 dla programu sklepu: w linii EAN i zaraz za nim ilość na 9 cyfrach, 6 całości i 3 po przecinku,
     * bez separatora (1 szt. = 000001000, 0,5 kg = 000000500). CRLF jak w .lst.
     */
    public byte[] proto(OrderDocument order) {
        StringBuilder text = new StringBuilder();
        for (Line line : order.orderedLines()) {
            if (line.quantity().compareTo(PROTO_MAX) > 0) {
                throw ApiException.badRequest("Ilość " + line.quantity() + " nie mieści się w pliku PROTO6");
            }
            long thousandths = line.quantity().movePointRight(3).longValueExact();
            text.append(line.ean()).append(String.format("%09d", thousandths)).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] xlsx(OrderDocument order) {
        List<List<Object>> rows = new ArrayList<>();
        for (Line line : order.orderedLines()) {
            rows.add(List.of(line.ean(), line.name() != null ? line.name() : "",
                    line.brand() != null ? line.brand() : "", line.quantity()));
        }
        return xlsxWriter.write("Zamówienie", List.of(
                new XlsxWriter.Column("EAN", 16),
                new XlsxWriter.Column("Nazwa", 42),
                new XlsxWriter.Column("Marka", 20),
                new XlsxWriter.Column("Ilość", 10)), rows);
    }

    public byte[] pdf(OrderDocument order) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            PageFooter footer = new PageFooter(font(regular, 8, MUTED), order);
            writer.setPageEvent(footer);
            document.addTitle("Zamówienie garmażu " + order.storeNumber() + " na " + order.deliveryDate());
            document.open();

            Map<String, Map<String, List<Line>>> sections = bySection(order.orderedLines());
            if (sections.isEmpty()) {
                writeHeader(document, order, null);
            }
            // Każdy dział na osobnej kartce: trafia do innego działu sklepu, więc nie może dzielić strony z innym.
            boolean first = true;
            for (Map.Entry<String, Map<String, List<Line>>> section : sections.entrySet()) {
                if (!first) {
                    document.newPage();
                }
                first = false;
                footer.startSection(section.getKey(), writer.getPageNumber());
                writeHeader(document, order, section.getKey());
                int number = 1;
                for (Map.Entry<String, List<Line>> category : section.getValue().entrySet()) {
                    number = writeCategory(document, writer.getDirectContent(), category.getKey(), category.getValue(),
                            number);
                }
                writeSignatures(document);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF zamówienia", e);
        }
        return out.toByteArray();
    }

    /** Nagłówek kartki działu: nazwa działu dużą czcionką, pod nią sklep, dostawca i daty. */
    private void writeHeader(Document document, OrderDocument order, String section) throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] {1, 1});
        header.setWidthPercentage(100);

        PdfPCell kicker = cell(new Phrase("Zamówienie garmażu · nr " + order.id(), font(regular, 9, MUTED)));
        kicker.setColspan(2);
        header.addCell(kicker);
        PdfPCell title = cell(new Phrase(section != null ? section : "Zamówienie garmażu", font(bold, 22, INK)));
        title.setColspan(2);
        title.setPaddingTop(2);
        title.setPaddingBottom(6);
        header.addCell(title);

        header.addCell(infoCell("Sklep zamawiający", order.storeNumber() + " · " + order.storeName(), false));
        header.addCell(infoCell("Dostawca", order.supplierNumber() + " · " + order.supplierName(), false));
        String submitted = SUBMITTED.format(order.submittedAt().atZone(STORE_ZONE))
                + (order.submittedBy() != null ? " · " + order.submittedBy() : "");
        header.addCell(infoCell("Złożone", submitted, false));
        header.addCell(infoCell("Dostawa", DELIVERY.format(order.deliveryDate()), true));
        document.add(header);
    }

    private PdfPCell infoCell(String label, String value, boolean emphasized) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label + "\n", font(regular, 8, MUTED)));
        phrase.add(new Phrase(value, font(emphasized ? bold : regular, emphasized ? 12 : 10, INK)));
        PdfPCell cell = cell(phrase);
        cell.setPaddingTop(8);
        cell.setLeading(0, 1.3f);
        return cell;
    }

    private int writeCategory(Document document, PdfContentByte canvas, String heading, List<Line> lines, int firstNumber)
            throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {5, 41, 14, 15, 25});
        table.setWidthPercentage(100);
        table.setSpacingBefore(18);
        // Nazwa kategorii i nagłówek kolumn powtarzają się, gdy tabela przechodzi na kolejną stronę.
        table.setHeaderRows(2);
        // Wiersz (nazwa, ilość, kod) nigdy nie rozdziela się między strony - przechodzi w całości na następną.
        table.setSplitRows(false);

        PdfPCell title = cell(new Phrase(heading, font(bold, 11, INK)));
        title.setColspan(5);
        title.setPaddingBottom(7);
        table.addCell(title);
        for (String label : List.of("Lp.", "Towar", "Zamówiono", "Zrealizowano", "Kod")) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font(bold, 8, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorderColor(RULE);
            cell.setPadding(5);
            cell.setHorizontalAlignment(label.equals("Towar") ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
            table.addCell(cell);
        }

        int number = firstNumber;
        for (Line line : lines) {
            table.addCell(bodyCell(new Phrase(String.valueOf(number++), font(regular, 9, MUTED)), Element.ALIGN_CENTER));
            table.addCell(bodyCell(new Phrase(line.name() != null ? line.name() : "-", font(regular, 10, INK)),
                    Element.ALIGN_LEFT));
            table.addCell(bodyCell(new Phrase(quantity(line), font(bold, 10, INK)), Element.ALIGN_CENTER));
            // Puste pole: odbierający wpisuje długopisem, ile faktycznie przyszło.
            table.addCell(bodyCell(new Phrase(" "), Element.ALIGN_CENTER));
            PdfPCell code = new PdfPCell(barcode(canvas, line.ean()), false);
            code.setBorderColor(RULE);
            code.setPadding(4);
            code.setHorizontalAlignment(Element.ALIGN_CENTER);
            code.setVerticalAlignment(Element.ALIGN_MIDDLE);
            code.setMinimumHeight(40);
            table.addCell(code);
        }
        document.add(table);
        return number;
    }

    private void writeSignatures(Document document) throws DocumentException {
        PdfPTable signatures = new PdfPTable(new float[] {1, 1, 1});
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(36);
        signatures.setKeepTogether(true);
        for (String label : List.of("Wydał", "Odebrał", "Data i godzina odbioru")) {
            PdfPCell cell = cell(new Phrase(label, font(regular, 8, MUTED)));
            cell.setBorder(Rectangle.TOP);
            cell.setBorderColor(MUTED);
            cell.setPaddingTop(4);
            cell.setPaddingRight(18);
            signatures.addCell(cell);
        }
        document.add(signatures);
    }

    private PdfPCell bodyCell(Phrase phrase, int alignment) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setPadding(5);
        cell.setMinimumHeight(40);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    /**
     * EAN-13 / EAN-8, gdy kod ma poprawną cyfrę kontrolną - tak jak na opakowaniu. Kody wewnętrzne sklepu
     * z błędną sumą kontrolną drukujemy jako Code 128, który czytnik odczyta dokładnie w tej postaci.
     */
    private static Image barcode(PdfContentByte canvas, String ean) {
        Barcode barcode;
        if (isValidEan(ean)) {
            BarcodeEAN code = new BarcodeEAN();
            code.setCodeType(ean.length() == 13 ? Barcode.EAN13 : Barcode.EAN8);
            barcode = code;
        } else {
            barcode = new Barcode128();
            barcode.setCodeType(Barcode.CODE128);
        }
        barcode.setCode(ean);
        barcode.setBarHeight(22);
        barcode.setX(0.85f);
        barcode.setSize(7);
        barcode.setBaseline(7);
        Image image = barcode.createImageWithBarcode(canvas, null, null);
        image.scaleToFit(118, 34);
        return image;
    }

    static boolean isValidEan(String code) {
        if (code == null || !(code.length() == 13 || code.length() == 8) || !code.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < code.length() - 1; i++) {
            int digit = code.charAt(code.length() - 2 - i) - '0';
            sum += i % 2 == 0 ? digit * 3 : digit;
        }
        return (10 - sum % 10) % 10 == code.charAt(code.length() - 1) - '0';
    }

    private static String quantity(Line line) {
        DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(POLISH));
        return format.format(line.quantity()) + (line.unit() == OrderUnit.KG ? " kg" : " szt.");
    }

    /** Dział → kategoria → pozycje, w kolejności pierwszego wystąpienia (pozycje są w kolejności formularza). */
    private static Map<String, Map<String, List<Line>>> bySection(List<Line> lines) {
        Map<String, Map<String, List<Line>>> sections = new LinkedHashMap<>();
        for (Line line : lines) {
            sections.computeIfAbsent(line.sectionName(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(line.categoryName(), key -> new ArrayList<>())
                    .add(line);
        }
        return sections;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    /**
     * Stopka: zamówienie, sklep, data dostawy, dział i strona w obrębie działu - kartki działów trafiają do różnych
     * osób, więc każda musi dać się rozpoznać sama.
     */
    private static final class PageFooter extends PdfPageEventHelper {

        private final Font font;
        private final OrderDocument order;
        private String section;
        private int sectionFirstPage = 1;

        PageFooter(Font font, OrderDocument order) {
            this.font = font;
            this.order = order;
        }

        void startSection(String name, int firstPage) {
            section = name;
            sectionFirstPage = firstPage;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            String left = "Zamówienie nr " + order.id() + " · sklep " + order.storeNumber() + " · dostawa "
                    + order.deliveryDate().format(DateTimeFormatter.ofPattern("d.MM.yyyy"));
            int page = writer.getPageNumber() - sectionFirstPage + 1;
            String right = (section != null ? section + " · " : "") + "strona " + page;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT, new Phrase(left, font),
                    document.left(), document.bottom() - 24, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, new Phrase(right, font),
                    document.right(), document.bottom() - 24, 0);
        }
    }
}
