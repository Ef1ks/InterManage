package com.intermanage.backend.cash.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.cash.entity.CashDocumentKind;
import com.intermanage.backend.common.component.AmountInWords;
import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
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
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Wydruki kasy w układzie dotychczasowego arkusza sklepu, prostsze:
 * <ul>
 * <li>dowód wpłaty KP albo wypłaty KW: A4 pionowo, oryginał i kopia do odcięcia, pieczątka, za co (operacja i opis),
 * kwota, słownie, podpisy, numer raportu kasowego i pozycja (po zakończeniu raportu), kontrahent na dole;</li>
 * <li>raport kasowy RK: dokumenty dnia (przychód, rozchód), obroty dnia, stan poprzedni i obecny, suma obu stron,
 * liczba załączników KP i KW, podpisy.</li>
 * </ul>
 * Anulowany dokument ma przekreślenie „ANULOWANY”.
 */
@Component
public class CashPdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(190, 190, 190);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final Color CANCELLED = new Color(200, 30, 30);

    /** Miejsce na pieczątkę: skan zmniejszany, żeby się zmieścił (w punktach). */
    private static final float STAMP_WIDTH = 170;
    private static final float STAMP_HEIGHT = 72;

    private final BaseFont regular;
    private final BaseFont bold;

    public CashPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /**
     * Dane jednego dokumentu do wydruku; {@code stamp} to bajty skanu PNG albo JPG. {@code reportNumber}
     * i {@code reportPosition} są znane dopiero po zakończeniu raportu dziennego (wcześniej puste pole).
     */
    public record Voucher(CashDocumentKind kind, String number, LocalDate issueDate, BigDecimal amount,
            String operation, String description, String counterparty, String counterpartyAddress, String issuedBy,
            byte[] stamp, String storeLine, boolean cancelled, String cancelReason, String reportNumber,
            Integer reportPosition) {
    }

    public record ReportRow(int position, LocalDate date, String number, String content, String counterparty,
            BigDecimal receipt, BigDecimal payment, boolean cancelled) {
    }

    /** Raport; {@code number} puste = raport bieżący, jeszcze niezakończony. */
    public record Report(String number, LocalDate from, LocalDate to, BigDecimal openingBalance, BigDecimal receipts,
            BigDecimal payments, BigDecimal closingBalance, int receiptCount, int paymentCount, List<ReportRow> rows,
            byte[] stamp, String storeLine, String printedBy, LocalDateTime printedAt) {
    }

    // ---------------------------------------------------------------- dowód KP / KW

    public byte[] voucher(Voucher data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 30, 30);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.addTitle(data.number());
            document.open();
            document.add(copy(data, "Oryginał"));
            Paragraph cut = new Paragraph("- ".repeat(98), font(regular, 8, RULE));
            cut.setSpacingBefore(12);
            cut.setSpacingAfter(12);
            document.add(cut);
            document.add(copy(data, "Kopia"));
            if (data.cancelled()) {
                cancelledMark(writer.getDirectContent(), document);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku " + data.number(), e);
        }
        return out.toByteArray();
    }

    private PdfPTable copy(Voucher data, String copyLabel) throws DocumentException {
        boolean receipt = data.kind().isReceipt();
        PdfPTable table = new PdfPTable(new float[] { 1.25f, 1 });
        table.setWidthPercentage(100);
        table.setKeepTogether(true);

        PdfPCell stamp = stampCell(data.stamp());
        stamp.setBorder(Rectangle.BOX);
        stamp.setBorderColor(RULE);
        table.addCell(stamp);

        PdfPCell heading = new PdfPCell();
        heading.setBorder(Rectangle.BOX);
        heading.setBorderColor(RULE);
        heading.setPadding(8);
        Paragraph title = new Paragraph();
        title.add(new Phrase(receipt ? "DOWÓD WPŁATY  " : "DOWÓD WYPŁATY  ", font(bold, 14, INK)));
        title.add(new Phrase(data.kind().name(), font(bold, 22, INK)));
        title.setAlignment(Element.ALIGN_RIGHT);
        heading.addElement(title);
        Paragraph number = new Paragraph("Nr " + data.number().substring(3), font(bold, 12, INK));
        number.setAlignment(Element.ALIGN_RIGHT);
        number.setSpacingBefore(6);
        heading.addElement(number);
        Paragraph date = new Paragraph("Dnia " + DATE.format(data.issueDate()), font(regular, 10.5f, INK));
        date.setAlignment(Element.ALIGN_RIGHT);
        heading.addElement(date);
        Paragraph copy = new Paragraph(copyLabel, font(regular, 8, MUTED));
        copy.setAlignment(Element.ALIGN_RIGHT);
        heading.addElement(copy);
        table.addCell(heading);

        PdfPTable body = new PdfPTable(new float[] { 3, 1 });
        body.setWidthPercentage(100);
        body.addCell(head("Za co"));
        body.addCell(head("Kwota zł"));
        Phrase what = new Phrase();
        what.add(new Phrase(data.operation(), font(bold, 11, INK)));
        if (data.description() != null) {
            what.add(new Phrase("\n" + data.description(), font(regular, 10.5f, INK)));
        }
        PdfPCell whatCell = cell(what);
        whatCell.setMinimumHeight(46);
        body.addCell(whatCell);
        PdfPCell amount = cell(new Phrase(money(data.amount()), font(bold, 13, INK)));
        amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
        amount.setVerticalAlignment(Element.ALIGN_MIDDLE);
        body.addCell(amount);
        PdfPCell bodyCell = new PdfPCell(body);
        bodyCell.setColspan(2);
        bodyCell.setBorder(Rectangle.NO_BORDER);
        bodyCell.setPaddingTop(4);
        table.addCell(bodyCell);

        table.addCell(labeled("Słownie", AmountInWords.of(data.amount()), 2));

        PdfPTable signatures = new PdfPTable(new float[] { 1, 1, 1, 1.05f, 1.5f });
        signatures.setWidthPercentage(100);
        signatures.addCell(signature("Wystawił", data.issuedBy()));
        signatures.addCell(signature("Sprawdził", null));
        signatures.addCell(signature("Zatwierdził", null));
        signatures.addCell(reportBox(data));
        signatures.addCell(signature(receipt ? "Kwotę powyższą otrzymałem (podpis kasjera)"
                : "Kwotę powyższą wypłaciłem / otrzymałem", null));
        PdfPCell signatureCell = new PdfPCell(signatures);
        signatureCell.setColspan(2);
        signatureCell.setBorder(Rectangle.NO_BORDER);
        signatureCell.setPaddingTop(8);
        table.addCell(signatureCell);

        if (data.counterparty() != null || data.counterpartyAddress() != null) {
            String party = (data.counterparty() == null ? "" : data.counterparty())
                    + (data.counterpartyAddress() == null ? "" : (data.counterparty() == null ? "" : ", ")
                            + data.counterpartyAddress());
            PdfPCell partyCell = new PdfPCell(new Phrase((receipt ? "Wpłacający: " : "Odbierający: ") + party,
                    font(regular, 8.5f, INK)));
            partyCell.setColspan(2);
            partyCell.setBorder(Rectangle.NO_BORDER);
            partyCell.setPaddingTop(6);
            table.addCell(partyCell);
        }

        String footer = data.storeLine()
                + (data.cancelled() ? "   ·   ANULOWANY" + (data.cancelReason() == null ? ""
                        : ": " + data.cancelReason()) : "");
        PdfPCell footerCell = new PdfPCell(new Phrase(footer,
                font(data.cancelled() ? bold : regular, 7.5f, data.cancelled() ? CANCELLED : MUTED)));
        footerCell.setColspan(2);
        footerCell.setBorder(Rectangle.NO_BORDER);
        footerCell.setPaddingTop(4);
        table.addCell(footerCell);
        return table;
    }

    /** „Raport kasowy Nr … Poz. …” jak na dotychczasowym druku; puste, dopóki raport dnia się nie zakończył. */
    private PdfPCell reportBox(Voucher data) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase("Raport kasowy\n", font(bold, 7.5f, MUTED)));
        phrase.add(new Phrase("Nr " + (data.reportNumber() == null ? "............" : data.reportNumber().substring(3))
                + "\n", font(regular, 8, INK)));
        phrase.add(new Phrase("Poz. " + (data.reportPosition() == null ? "......" : data.reportPosition()),
                font(regular, 8, INK)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setPadding(4);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    // ---------------------------------------------------------------- raport kasowy

    public byte[] report(Report data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 32, 32, 30, 46);
        String period = data.from().equals(data.to()) ? DATE.format(data.from())
                : DATE.format(data.from()) + " – " + DATE.format(data.to());
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7.5f, MUTED), "Raport kasowy "
                    + (data.number() == null ? "" : data.number().substring(3) + " ") + "za " + period + " · "
                    + data.storeLine() + " · wydrukował " + data.printedBy() + " " + STAMP.format(data.printedAt())));
            document.addTitle("Raport kasowy");
            document.open();

            PdfPTable header = new PdfPTable(new float[] { 1.25f, 1 });
            header.setWidthPercentage(100);
            PdfPCell stamp = stampCell(data.stamp());
            stamp.setBorder(Rectangle.BOX);
            stamp.setBorderColor(RULE);
            header.addCell(stamp);
            PdfPCell heading = new PdfPCell();
            heading.setBorder(Rectangle.BOX);
            heading.setBorderColor(RULE);
            heading.setPadding(8);
            Paragraph title = new Paragraph("RAPORT KASOWY", font(bold, 16, INK));
            title.setAlignment(Element.ALIGN_RIGHT);
            heading.addElement(title);
            Paragraph number = new Paragraph(data.number() == null ? "bieżący, niezakończony"
                    : "RK Nr " + data.number().substring(3), font(bold, data.number() == null ? 10 : 13,
                            data.number() == null ? MUTED : INK));
            number.setAlignment(Element.ALIGN_RIGHT);
            heading.addElement(number);
            Paragraph periodLine = new Paragraph("za " + (data.from().equals(data.to()) ? "dzień " : "okres ")
                    + period, font(regular, 10, INK));
            periodLine.setAlignment(Element.ALIGN_RIGHT);
            heading.addElement(periodLine);
            header.addCell(heading);
            header.setSpacingAfter(12);
            document.add(header);

            PdfPTable table = new PdfPTable(new float[] { 0.75f, 1.3f, 1.75f, 4.1f, 1.4f, 1.4f });
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String column : List.of("Poz.", "Data", "Dowód", "Treść", "Przychód", "Rozchód")) {
                table.addCell(head(column));
            }
            if (data.rows().isEmpty()) {
                PdfPCell empty = cell(new Phrase("Brak dokumentów", font(regular, 8.5f, MUTED)));
                empty.setColspan(6);
                table.addCell(empty);
            }
            for (ReportRow row : data.rows()) {
                Color ink = row.cancelled() ? MUTED : INK;
                Font text = font(regular, 8.5f, ink);
                table.addCell(textCell(String.valueOf(row.position()), text, Element.ALIGN_RIGHT));
                table.addCell(textCell(DATE.format(row.date()), text, Element.ALIGN_LEFT));
                table.addCell(textCell(row.number(), font(bold, 8.5f, ink), Element.ALIGN_LEFT));
                Phrase content = new Phrase(row.content(), text);
                if (row.counterparty() != null) {
                    content.add(new Phrase("\n" + row.counterparty(), font(regular, 7.5f, MUTED)));
                }
                if (row.cancelled()) {
                    content.add(new Phrase("\nANULOWANY", font(bold, 7.5f, CANCELLED)));
                }
                table.addCell(cell(content));
                table.addCell(amountCell(row.receipt(), false));
                table.addCell(amountCell(row.payment(), false));
            }
            document.add(table);
            document.add(summary(data));

            PdfPTable signatures = new PdfPTable(new float[] { 1, 1, 1 });
            signatures.setWidthPercentage(100);
            signatures.setSpacingBefore(36);
            signatures.setKeepTogether(true);
            signatures.addCell(signature("Podpis sporządzającego", null));
            signatures.addCell(signature("Podpis sprawdzającego", null));
            signatures.addCell(signature("Księgowano", null));
            document.add(signatures);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć raportu kasowego", e);
        }
        return out.toByteArray();
    }

    /**
     * Podsumowanie jak w dotychczasowym raporcie: obroty dnia, stan poprzedni po stronie przychodu, stan obecny po
     * stronie rozchodu, suma obu stron (równa) i liczba załączników KP i KW.
     */
    private PdfPTable summary(Report data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1.4f, 1.3f, 1.4f, 1.4f, 0.9f, 0.6f });
        table.setWidthPercentage(68);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(10);
        table.setKeepTogether(true);
        table.addCell(head("Obroty"));
        table.addCell(label("dnia"));
        table.addCell(amountCell(data.receipts(), true));
        table.addCell(amountCell(data.payments(), true));
        PdfPCell attachments = head("Ilość załączników");
        attachments.setColspan(2);
        table.addCell(attachments);

        PdfPCell balance = head("Stan kasy");
        balance.setRowspan(2);
        table.addCell(balance);
        table.addCell(label("poprzedni"));
        table.addCell(amountCell(data.openingBalance(), true));
        table.addCell(cell(new Phrase("")));
        table.addCell(label("KP"));
        table.addCell(textCell(String.valueOf(data.receiptCount()), font(bold, 9, INK), Element.ALIGN_CENTER));
        table.addCell(label("obecny"));
        table.addCell(cell(new Phrase("")));
        table.addCell(amountCell(data.closingBalance(), true));
        table.addCell(label("KW"));
        table.addCell(textCell(String.valueOf(data.paymentCount()), font(bold, 9, INK), Element.ALIGN_CENTER));

        PdfPCell sum = head("SUMA");
        sum.setColspan(2);
        table.addCell(sum);
        table.addCell(amountCell(data.openingBalance().add(data.receipts()), true));
        table.addCell(amountCell(data.payments().add(data.closingBalance()), true));
        PdfPCell blank = cell(new Phrase(""));
        blank.setColspan(2);
        table.addCell(blank);

        PdfPCell words = new PdfPCell(new Phrase("Stan kasy obecny słownie: " + AmountInWords.of(data.closingBalance()),
                font(regular, 8.5f, INK)));
        words.setColspan(6);
        words.setBorder(Rectangle.NO_BORDER);
        words.setPaddingTop(6);
        table.addCell(words);
        return table;
    }

    // ---------------------------------------------------------------- części

    /** Pieczątka ze skanu w polu o stałej wielkości; bez skanu puste pole z podpisem. */
    private PdfPCell stampCell(byte[] content) {
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(STAMP_HEIGHT + 16);
        cell.setPadding(8);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (content != null) {
            try {
                Image image = Image.getInstance(content);
                image.scaleToFit(STAMP_WIDTH, STAMP_HEIGHT);
                cell.addElement(image);
                return cell;
            } catch (IOException | RuntimeException e) {
                // Uszkodzony skan: wydruk z pustym polem, zamiast błędu całego dokumentu.
            }
        }
        cell.addElement(new Phrase("pieczęć", font(regular, 8, MUTED)));
        return cell;
    }

    private void cancelledMark(PdfContentByte canvas, Document document) {
        canvas.saveState();
        PdfGState state = new PdfGState();
        state.setFillOpacity(0.18f);
        canvas.setGState(state);
        canvas.beginText();
        canvas.setFontAndSize(bold, 66);
        canvas.setColorFill(CANCELLED);
        float x = (document.left() + document.right()) / 2;
        float y = (document.top() + document.bottom()) / 2;
        canvas.showTextAligned(Element.ALIGN_CENTER, "ANULOWANY", x, y + 200, 25);
        canvas.showTextAligned(Element.ALIGN_CENTER, "ANULOWANY", x, y - 200, 25);
        canvas.endText();
        canvas.restoreState();
    }

    private PdfPCell labeled(String label, String value, int colspan) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(label + "\n", font(regular, 8, MUTED)));
        phrase.add(new Phrase(value, font(bold, 10.5f, INK)));
        PdfPCell cell = cell(phrase);
        cell.setColspan(colspan);
        return cell;
    }

    private PdfPCell signature(String label, String name) {
        Phrase phrase = new Phrase();
        phrase.add(new Phrase(name == null ? "\n" : name + "\n", font(regular, 8.5f, INK)));
        phrase.add(new Phrase("\n.........................\n", font(regular, 8, RULE)));
        phrase.add(new Phrase(label, font(regular, 7.5f, MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPaddingTop(4);
        return cell;
    }

    private PdfPCell head(String text) {
        PdfPCell cell = cell(new Phrase(text, font(bold, 8.5f, INK)));
        cell.setBackgroundColor(HEAD);
        return cell;
    }

    private PdfPCell label(String text) {
        PdfPCell cell = cell(new Phrase(text, font(regular, 8, MUTED)));
        cell.setBackgroundColor(HEAD);
        return cell;
    }

    private PdfPCell textCell(String text, Font font, int alignment) {
        PdfPCell cell = cell(new Phrase(text, font));
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private PdfPCell amountCell(BigDecimal amount, boolean strong) {
        PdfPCell cell = cell(new Phrase(amount == null ? "" : money(amount), font(strong ? bold : regular, 8.5f, INK)));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setPadding(5);
        return cell;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.forLanguageTag("pl-PL"));
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols).format(amount);
    }
}
