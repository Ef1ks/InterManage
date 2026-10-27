package com.intermanage.backend.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.intermanage.backend.component.KsefInvoiceReader.Block;
import com.intermanage.backend.component.KsefInvoiceReader.Entry;
import com.intermanage.backend.component.KsefInvoiceReader.Invoice;
import com.intermanage.backend.component.KsefInvoiceReader.Line;
import com.intermanage.backend.component.KsefInvoiceReader.Party;
import com.intermanage.backend.component.KsefInvoiceReader.Table;
import com.intermanage.backend.component.KsefInvoiceReader.TaxRow;
import com.lowagie.text.BadElementException;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
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
 * Faktura z KSeF jako PDF do czytania i wydruku, zrobiona z jej XML: nagłówek z numerem KSeF i kodem QR
 * weryfikacji (KOD I z KSeF), strony transakcji, pozycje, podsumowanie stawek, płatność i pozostałe dane z pliku.
 * Puste sekcje są pomijane. Dokumentem pozostaje XML w KSeF.
 */
@Component
public class KsefInvoicePdf {

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", POLISH);
    private static final DateTimeFormatter QR_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(212, 212, 212);
    private static final Color BAND = new Color(244, 244, 245);
    private static final float[] ENTRY_WIDTHS = {30, 70};

    private final KsefInvoiceReader reader;
    private final BaseFont regular;
    private final BaseFont bold;
    private final String verificationBaseUrl;

    public KsefInvoicePdf(KsefInvoiceReader reader, PdfFonts fonts, @Value("${app.ksef.base-url}") String apiUrl) {
        this.reader = reader;
        this.regular = fonts.regular();
        this.bold = fonts.bold();
        this.verificationBaseUrl = verificationBaseUrl(apiUrl);
    }

    /**
     * Adres weryfikacji faktury dla środowiska z {@code app.ksef.base-url}: api.ksef.mf.gov.pl → qr.ksef.mf.gov.pl,
     * api-test → qr-test, api-demo → qr-demo.
     */
    public static String verificationBaseUrl(String apiUrl) {
        String host = URI.create(apiUrl).getHost();
        return "https://" + host.replaceFirst("^api", "qr");
    }

    /**
     * KOD I z KSeF: {@code /invoice/{NIP sprzedawcy}/{data wystawienia DD-MM-RRRR}/{SHA-256 pliku w Base64URL}}.
     * Po zeskanowaniu KSeF pokazuje, czy faktura o tej treści jest w systemie.
     */
    public String verificationUrl(byte[] xml, String sellerNip, LocalDate issueDate) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(xml);
            return verificationBaseUrl + "/invoice/" + sellerNip + "/" + QR_DATE.format(issueDate) + "/"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code acceptedAt}: data nadania numeru KSeF. Rzuca {@link KsefInvoiceReader.InvalidInvoiceException}. */
    public byte[] pdf(byte[] xml, String ksefNumber, String sellerNip, Instant acceptedAt) {
        Invoice invoice = reader.read(xml);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageFooter(font(regular, 7.5f, MUTED),
                    invoice.kind() + " " + Objects.toString(invoice.number(), "") + " · KSeF " + ksefNumber));
            document.addTitle(invoice.kind() + " " + Objects.toString(invoice.number(), ksefNumber));
            document.addSubject("Faktura z KSeF " + ksefNumber);
            document.addCreator("Intermanage");
            document.open();

            String qr = invoice.issueDate() != null ? verificationUrl(xml, sellerNip, invoice.issueDate()) : null;
            writeHeader(document, writer.getDirectContent(), invoice, ksefNumber, qr);
            writeDates(document, invoice, acceptedAt);
            writeParties(document, invoice);
            entries(document, "Korekta", invoice.correctionDetails());
            if (!invoice.lines().isEmpty()) {
                document.add(linesTable("Pozycje", invoice.lines()));
            }
            writeTotals(document, invoice);
            if (!invoice.annotations().isEmpty()) {
                PdfPTable annotations = sectionTable("Adnotacje", new float[] {1}, 0);
                for (String annotation : invoice.annotations()) {
                    textRow(annotations, "•  " + annotation, font(bold, 8.5f, INK));
                }
                document.add(annotations);
            }
            entries(document, "Płatność", invoice.payment());
            entries(document, "Rozliczenie", invoice.settlement());
            entries(document, "Warunki transakcji", invoice.transaction());
            entries(document, "Dodatkowe informacje", invoice.additional());
            if (!invoice.orderLines().isEmpty()) {
                document.add(linesTable("Zamówienie" + (invoice.orderValue() != null
                        ? " · wartość " + KsefInvoiceReader.money(invoice.orderValue()) + " " + currency(invoice)
                        : ""), invoice.orderLines()));
            }
            for (Block block : invoice.attachment()) {
                writeBlock(document, block);
            }
            if (!invoice.footerNotes().isEmpty() || !invoice.registries().isEmpty()) {
                PdfPTable footer = sectionTable("Informacje sprzedawcy", ENTRY_WIDTHS, 0);
                for (String note : invoice.footerNotes()) {
                    textRow(footer, note, font(regular, 8, INK));
                }
                addEntries(footer, invoice.registries());
                document.add(footer);
            }
            writeSource(document, invoice, ksefNumber);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć PDF faktury", e);
        }
        return out.toByteArray();
    }

    /** Rodzaj i numer faktury po lewej, kod QR weryfikacji z numerem KSeF po prawej. */
    private void writeHeader(Document document, PdfContentByte canvas, Invoice invoice, String ksefNumber, String qr)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] {64, 36});
        header.setWidthPercentage(100);

        Phrase title = new Phrase();
        title.add(new Phrase("Faktura z KSeF" + (invoice.formCode() != null ? " · " + invoice.formCode() : "")
                + "\n", font(regular, 8, MUTED)));
        title.add(new Phrase(invoice.kind() + "\n", font(bold, 18, INK)));
        title.add(new Phrase("Nr " + Objects.toString(invoice.number(), "–"), font(bold, 12, INK)));
        PdfPCell left = cell(title);
        left.setLeading(0, 1.45f);
        left.setVerticalAlignment(Element.ALIGN_TOP);
        header.addCell(left);

        PdfPTable right = new PdfPTable(1);
        if (qr != null) {
            PdfPCell code = new PdfPCell(qr(canvas, qr, 78), false);
            code.setBorder(Rectangle.NO_BORDER);
            code.setHorizontalAlignment(Element.ALIGN_RIGHT);
            code.setPaddingBottom(3);
            right.addCell(code);
        }
        Phrase number = new Phrase();
        number.add(new Phrase("Numer KSeF\n", font(regular, 7, MUTED)));
        number.add(new Phrase(ksefNumber, font(bold, 7.5f, INK)));
        if (qr != null) {
            number.add(new Phrase("\nZeskanuj kod, aby sprawdzić fakturę w KSeF", font(regular, 6.5f, MUTED)));
        }
        PdfPCell numberCell = cell(number);
        numberCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        numberCell.setLeading(0, 1.3f);
        right.addCell(numberCell);
        PdfPCell rightCell = new PdfPCell(right);
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setPadding(0);
        header.addCell(rightCell);
        document.add(header);
    }

    private void writeDates(Document document, Invoice invoice, Instant acceptedAt) throws DocumentException {
        String saleLabel = "Data dostawy / wykonania";
        String sale = KsefInvoiceReader.formatDate(invoice.saleDate());
        if (sale == null && (invoice.periodFrom() != null || invoice.periodTo() != null)) {
            saleLabel = "Okres";
            sale = Objects.toString(KsefInvoiceReader.formatDate(invoice.periodFrom()), "…") + " – "
                    + Objects.toString(KsefInvoiceReader.formatDate(invoice.periodTo()), "…");
        }
        PdfPTable dates = new PdfPTable(4);
        dates.setWidthPercentage(100);
        dates.setSpacingBefore(14);
        dates.addCell(infoCell("Data wystawienia", KsefInvoiceReader.formatDate(invoice.issueDate()),
                invoice.issuePlace()));
        dates.addCell(infoCell(saleLabel, sale, null));
        dates.addCell(infoCell("Waluta", invoice.currency(), null));
        dates.addCell(infoCell("Przyjęta w KSeF",
                acceptedAt != null ? DATE_TIME.format(acceptedAt.atZone(STORE_ZONE)) : null, null));
        document.add(dates);
    }

    private PdfPCell infoCell(String label, String value, String note) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label + "\n", font(regular, 7, MUTED)));
        phrase.add(new Phrase(value != null ? value : "–", font(bold, 9, INK)));
        if (note != null) {
            phrase.add(new Phrase("\n" + note, font(regular, 7.5f, INK)));
        }
        PdfPCell cell = cell(phrase);
        cell.setBackgroundColor(BAND);
        cell.setPadding(7);
        cell.setPaddingTop(5);
        cell.setLeading(0, 1.35f);
        return cell;
    }

    private void writeParties(Document document, Invoice invoice) throws DocumentException {
        List<Party> parties = new ArrayList<>();
        parties.add(invoice.seller());
        parties.add(invoice.buyer());
        parties.addAll(invoice.otherParties());
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(14);
        for (Party party : parties) {
            table.addCell(partyCell(party));
        }
        if (parties.size() % 2 == 1) {
            table.addCell(cell(new Phrase(" ")));
        }
        document.add(table);
    }

    private PdfPCell partyCell(Party party) {
        Phrase phrase = new Phrase();
        if (party == null) {
            phrase.add(new Phrase("–", font(regular, 9, MUTED)));
        } else {
            phrase.add(new Phrase(party.role().toUpperCase(POLISH) + "\n", font(bold, 7, MUTED)));
            phrase.add(new Phrase(Objects.toString(party.name(), "–") + "\n", font(bold, 10, INK)));
            for (String identifier : party.identifiers()) {
                phrase.add(new Phrase(identifier + "\n", font(bold, 8.5f, INK)));
            }
            for (String line : party.address()) {
                phrase.add(new Phrase(line + "\n", font(regular, 8.5f, INK)));
            }
            for (String detail : party.details()) {
                phrase.add(new Phrase(detail + "\n", font(regular, 7.5f, MUTED)));
            }
        }
        PdfPCell cell = cell(phrase);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColor(RULE);
        cell.setPaddingTop(6);
        cell.setPaddingRight(14);
        cell.setPaddingBottom(10);
        cell.setLeading(0, 1.35f);
        return cell;
    }

    /** Kolumny tylko dla danych, które faktura ma (np. cena brutto przy fakturze liczonej od brutto). */
    private PdfPTable linesTable(String title, List<Line> lines) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("Lp.", 4, Element.ALIGN_CENTER, Line::number));
        columns.add(null);
        columns.add(new Column("Ilość", 9, Element.ALIGN_RIGHT, KsefInvoicePdf::quantity));
        if (lines.stream().anyMatch(line -> line.unitNet() != null)) {
            columns.add(new Column("Cena jedn. netto", 10, Element.ALIGN_RIGHT, line -> unitPrice(line.unitNet())));
        }
        if (lines.stream().anyMatch(line -> line.unitGross() != null)) {
            columns.add(new Column("Cena jedn. brutto", 10, Element.ALIGN_RIGHT,
                    line -> unitPrice(line.unitGross())));
        }
        if (lines.stream().anyMatch(line -> line.discount() != null)) {
            columns.add(new Column("Rabat", 8, Element.ALIGN_RIGHT,
                    line -> KsefInvoiceReader.money(line.discount())));
        }
        columns.add(new Column("VAT", 7, Element.ALIGN_CENTER, Line::rate));
        if (lines.stream().anyMatch(line -> line.net() != null)) {
            columns.add(new Column("Wartość netto", 11, Element.ALIGN_RIGHT,
                    line -> KsefInvoiceReader.money(line.net())));
        }
        if (lines.stream().anyMatch(line -> line.vat() != null)) {
            columns.add(new Column("Kwota VAT", 10, Element.ALIGN_RIGHT, line -> KsefInvoiceReader.money(line.vat())));
        }
        if (lines.stream().anyMatch(line -> line.gross() != null)) {
            columns.add(new Column("Wartość brutto", 11, Element.ALIGN_RIGHT,
                    line -> KsefInvoiceReader.money(line.gross())));
        }
        float fixed = (float) columns.stream().filter(Objects::nonNull).mapToDouble(Column::width).sum();
        // Nazwa dostaje to, co zostało - przy kilku kolumnach kwot ma najmniej 30%.
        columns.set(1, new Column("Nazwa towaru lub usługi", Math.max(100 - fixed, 30), Element.ALIGN_LEFT, null));

        float[] widths = new float[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).width();
        }
        PdfPTable table = sectionTable(title, widths, 1);
        for (Column column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), font(bold, 7, MUTED)));
            cell.setBackgroundColor(BAND);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(4);
            cell.setHorizontalAlignment(column.alignment());
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(cell);
        }
        for (Line line : lines) {
            Color color = line.beforeCorrection() ? MUTED : INK;
            for (Column column : columns) {
                Phrase phrase;
                if (column.value() == null) {
                    phrase = new Phrase();
                    if (line.beforeCorrection()) {
                        phrase.add(new Phrase("Przed korektą\n", font(bold, 6.5f, MUTED)));
                    }
                    phrase.add(new Phrase(Objects.toString(line.name(), "–"), font(regular, 8, color)));
                    if (!line.details().isEmpty()) {
                        phrase.add(new Phrase("\n" + String.join(" · ", line.details()), font(regular, 6.5f, MUTED)));
                    }
                } else {
                    String value = column.value().apply(line);
                    phrase = new Phrase(value != null ? value : "", font(column.label().equals("Wartość brutto")
                            ? bold : regular, 8, color));
                }
                PdfPCell cell = new PdfPCell(phrase);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(RULE);
                cell.setPadding(4);
                cell.setPaddingBottom(5);
                cell.setLeading(0, 1.25f);
                cell.setHorizontalAlignment(column.alignment());
                table.addCell(cell);
            }
        }
        if (lines.stream().anyMatch(Line::grossComputed)) {
            textRow(table, "Ceny i wartości brutto pozycji wyliczone z netto i stawki VAT - faktura podaje tylko netto.",
                    font(regular, 6.5f, MUTED));
        }
        return table;
    }

    private record Column(String label, float width, int alignment, Function<Line, String> value) {
    }

    /** Podsumowanie według stawek (prawa strona) i kwota należności. */
    private void writeTotals(Document document, Invoice invoice) throws DocumentException {
        String currency = currency(invoice);
        if (!invoice.taxes().isEmpty()) {
            boolean pln = invoice.taxes().stream().anyMatch(row -> row.vatPln() != null);
            PdfPTable table = new PdfPTable(pln ? new float[] {30, 18, 16, 16, 20} : new float[] {34, 22, 20, 24});
            table.setWidthPercentage(pln ? 72 : 62);
            table.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.setSpacingBefore(12);
            table.setKeepTogether(true);
            List<String> headers = new ArrayList<>(List.of("Stawka", "Netto", "VAT"));
            if (pln) {
                headers.add("VAT w PLN");
            }
            headers.add("Brutto");
            for (String label : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(label, font(bold, 7, MUTED)));
                cell.setBackgroundColor(BAND);
                cell.setBorder(Rectangle.NO_BORDER);
                cell.setPadding(4);
                cell.setHorizontalAlignment(label.equals("Stawka") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                table.addCell(cell);
            }
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal vat = BigDecimal.ZERO;
            for (TaxRow row : invoice.taxes()) {
                BigDecimal rowVat = row.vat() != null ? row.vat() : BigDecimal.ZERO;
                net = net.add(row.net());
                vat = vat.add(rowVat);
                List<String> values = new ArrayList<>(List.of(row.rate(), KsefInvoiceReader.money(row.net()),
                        row.vat() != null ? KsefInvoiceReader.money(row.vat()) : "–"));
                if (pln) {
                    values.add(row.vatPln() != null ? KsefInvoiceReader.money(row.vatPln()) : "–");
                }
                values.add(KsefInvoiceReader.money(row.net().add(rowVat)));
                totalsRow(table, values, false);
            }
            if (invoice.taxes().size() > 1) {
                List<String> values = new ArrayList<>(List.of("Razem", KsefInvoiceReader.money(net),
                        KsefInvoiceReader.money(vat)));
                if (pln) {
                    values.add("");
                }
                values.add(KsefInvoiceReader.money(net.add(vat)));
                totalsRow(table, values, true);
            }
            document.add(table);
        }
        if (invoice.total() != null) {
            PdfPTable total = new PdfPTable(1);
            total.setWidthPercentage(62);
            total.setHorizontalAlignment(Element.ALIGN_RIGHT);
            total.setSpacingBefore(8);
            total.setKeepTogether(true);
            Phrase phrase = new Phrase();
            phrase.add(new Phrase((invoice.correction() ? "Kwota korekty należności" : "Kwota należności ogółem")
                    + "\n", font(regular, 8, MUTED)));
            phrase.add(new Phrase(KsefInvoiceReader.money(invoice.total()) + " " + currency, font(bold, 16, INK)));
            PdfPCell cell = cell(phrase);
            cell.setBorder(Rectangle.TOP);
            cell.setBorderWidthTop(1.2f);
            cell.setBorderColor(INK);
            cell.setPaddingTop(6);
            cell.setLeading(0, 1.35f);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            total.addCell(cell);
            document.add(total);
        }
    }

    private void totalsRow(PdfPTable table, List<String> values, boolean emphasized) {
        for (int i = 0; i < values.size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(values.get(i), font(emphasized ? bold : regular, 8, INK)));
            cell.setBorder(emphasized ? Rectangle.TOP : Rectangle.BOTTOM);
            cell.setBorderColor(emphasized ? MUTED : RULE);
            cell.setPadding(4);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    /** Blok załącznika; jego tabele są wierszami sekcji, żeby tytuł nie odrywał się od treści. */
    private void writeBlock(Document document, Block block) throws DocumentException {
        PdfPTable section = sectionTable("Załącznik" + (block.title() != null ? " · " + block.title() : ""),
                ENTRY_WIDTHS, 0);
        addEntries(section, block.meta());
        for (String paragraph : block.paragraphs()) {
            textRow(section, paragraph, font(regular, 8, INK));
        }
        for (Table table : block.tables()) {
            if (table.description() != null) {
                textRow(section, table.description(), font(bold, 8.5f, INK));
            }
            addEntries(section, table.meta());
            int columns = Math.max(table.header().size(), table.rows().stream().mapToInt(List::size).max().orElse(0));
            if (columns == 0) {
                continue;
            }
            PdfPTable grid = new PdfPTable(columns);
            grid.setWidthPercentage(100);
            grid.setHeaderRows(table.header().isEmpty() ? 0 : 1);
            if (!table.header().isEmpty()) {
                gridRow(grid, table.header(), columns, font(bold, 7, MUTED), BAND);
            }
            for (List<String> row : table.rows()) {
                gridRow(grid, row, columns, font(regular, 7.5f, INK), null);
            }
            if (!table.sum().isEmpty()) {
                gridRow(grid, table.sum(), columns, font(bold, 7.5f, INK), null);
            }
            PdfPCell cell = new PdfPCell(grid);
            cell.setColspan(ENTRY_WIDTHS.length);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(0);
            cell.setPaddingTop(4);
            cell.setPaddingBottom(6);
            section.addCell(cell);
        }
        if (section.size() == section.getHeaderRows()) {
            textRow(section, " ", font(regular, 8, INK));
        }
        document.add(section);
    }

    private static void gridRow(PdfPTable grid, List<String> values, int columns, Font font, Color background) {
        for (int i = 0; i < columns; i++) {
            String value = i < values.size() ? values.get(i) : null;
            PdfPCell cell = new PdfPCell(new Phrase(value != null ? value : "", font));
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColor(RULE);
            cell.setPadding(3);
            if (background != null) {
                cell.setBackgroundColor(background);
                cell.setBorder(Rectangle.NO_BORDER);
            }
            grid.addCell(cell);
        }
    }

    /** Skąd jest wydruk: oryginałem faktury jest XML w KSeF. */
    private void writeSource(Document document, Invoice invoice, String ksefNumber) throws DocumentException {
        StringBuilder text = new StringBuilder("Wizualizacja faktury pobranej z KSeF. Oryginałem jest plik XML"
                + " faktury o numerze KSeF " + ksefNumber + ".");
        if (invoice.createdAt() != null || invoice.system() != null) {
            text.append(" Faktura utworzona");
            if (invoice.createdAt() != null) {
                text.append(" ").append(DATE_TIME.format(invoice.createdAt().atZoneSameInstant(STORE_ZONE)));
            }
            if (invoice.system() != null) {
                text.append(" w systemie ").append(invoice.system());
            }
            text.append(".");
        }
        Paragraph paragraph = new Paragraph(text.toString(), font(regular, 7, MUTED));
        paragraph.setSpacingBefore(18);
        paragraph.setLeading(0, 1.35f);
        document.add(paragraph);
    }

    private void entries(Document document, String title, List<Entry> entries) throws DocumentException {
        if (!entries.isEmpty()) {
            PdfPTable table = sectionTable(title, ENTRY_WIDTHS, 0);
            addEntries(table, entries);
            document.add(table);
        }
    }

    /**
     * Tabela sekcji z tytułem (i odstępem pod nim) w wierszach nagłówka: tytuł nie zostaje sam na dole strony,
     * a gdy sekcja przechodzi na następną stronę, powtarza się tam razem z {@code extraHeaderRows} (np. kolumnami).
     */
    private PdfPTable sectionTable(String title, float[] widths, int extraHeaderRows) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingBefore(14);
        table.setHeaderRows(2 + extraHeaderRows);
        table.setSplitLate(true);
        PdfPCell heading = cell(new Phrase(title, font(bold, 10, INK)));
        heading.setColspan(widths.length);
        heading.setBorder(Rectangle.BOTTOM);
        heading.setBorderColor(RULE);
        heading.setPaddingBottom(5);
        table.addCell(heading);
        PdfPCell gap = cell(new Phrase(" ", font(regular, 3, INK)));
        gap.setColspan(widths.length);
        table.addCell(gap);
        return table;
    }

    private static void textRow(PdfPTable table, String text, Font font) {
        PdfPCell cell = cell(new Phrase(text, font));
        cell.setColspan(table.getNumberOfColumns());
        cell.setPaddingTop(2);
        cell.setPaddingBottom(3);
        cell.setLeading(0, 1.35f);
        table.addCell(cell);
    }

    private void addEntries(PdfPTable table, List<Entry> entries) {
        for (Entry entry : entries) {
            PdfPCell label = cell(new Phrase(Objects.toString(entry.label(), ""), font(regular, 8, MUTED)));
            label.setPadding(2.5f);
            label.setPaddingLeft(0);
            table.addCell(label);
            PdfPCell value = cell(new Phrase(Objects.toString(entry.value(), "–"), font(regular, 8.5f, INK)));
            value.setPadding(2.5f);
            value.setLeading(0, 1.3f);
            table.addCell(value);
        }
    }

    private static String currency(Invoice invoice) {
        return invoice.currency() != null ? invoice.currency() : "PLN";
    }

    private static String quantity(Line line) {
        if (line.quantity() == null) {
            return line.unit();
        }
        String quantity = new DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(POLISH))
                .format(line.quantity());
        return line.unit() != null ? quantity + " " + line.unit() : quantity;
    }

    /** Cena jednostkowa: co najmniej dwa miejsca, a dalsze tylko wtedy, gdy są w pliku. */
    private static String unitPrice(BigDecimal price) {
        return price != null
                ? new DecimalFormat("#,##0.00######", DecimalFormatSymbols.getInstance(POLISH)).format(price) : null;
    }

    /** Kod QR jako wektor: ciągi modułów w wierszu łączone w jeden prostokąt, żeby nie było szczelin. */
    private static Image qr(PdfContentByte canvas, String content, float size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            int modules = matrix.getWidth();
            PdfTemplate template = canvas.createTemplate(modules, modules);
            template.setColorFill(INK);
            for (int y = 0; y < modules; y++) {
                int x = 0;
                while (x < modules) {
                    if (!matrix.get(x, y)) {
                        x++;
                        continue;
                    }
                    int start = x;
                    while (x < modules && matrix.get(x, y)) {
                        x++;
                    }
                    template.rectangle(start, modules - 1 - y, x - start, 1);
                }
            }
            template.fill();
            Image image = Image.getInstance(template);
            image.scaleAbsolute(size, size);
            return image;
        } catch (WriterException | BadElementException e) {
            throw new IllegalStateException("Nie udało się utworzyć kodu QR", e);
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

    /** Stopka: faktura i numer KSeF po lewej, "Strona X z Y" po prawej (liczba stron dopisywana na końcu). */
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
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(TOTAL_WIDTH, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            pages = writer.getPageNumber();
            PdfContentByte canvas = writer.getDirectContent();
            float y = document.bottom() - 24;
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase(text, font), document.left(), y, 0);
            float right = document.right() - TOTAL_WIDTH;
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Strona " + pages + " z", font),
                    right, y, 0);
            canvas.addTemplate(total, right + 3, y - 3);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(total, Element.ALIGN_LEFT, new Phrase(String.valueOf(pages), font), 0, 3, 0);
        }
    }
}
