package com.intermanage.backend.staff.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.lowagie.text.Chunk;
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
 * Wydruk do akt (A4 pionowo) dla czasu poza grafikiem przyjętego do czasu pracy:
 * <ul>
 * <li>{@link Variant#EMPLOYER_ORDER}: polecenie pracy w godzinach nadliczbowych (art. 151 § 1 KP: szczególne
 * potrzeby pracodawcy albo akcja ratownicza, do zaznaczenia).</li>
 * <li>{@link Variant#EMPLOYEE_REQUEST}: wniosek pracownika o zgodę na pracę w godzinach nadliczbowych ze zgodą
 * pracodawcy, z wyborem rekompensaty: czas wolny w tym samym wymiarze na wniosek pracownika (art. 151² § 1) albo
 * dodatek do wynagrodzenia (art. 151¹).</li>
 * </ul>
 * Rodzaj rekompensaty i przyczynę zaznacza się długopisem, bo zależą od ustaleń z pracownikiem.
 */
@Component
public class OvertimePdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(200, 200, 200);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final String BOX = "☐  ";

    private final BaseFont regular;
    private final BaseFont bold;

    public OvertimePdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public enum Variant {
        EMPLOYER_ORDER,
        EMPLOYEE_REQUEST
    }

    public record Employer(String name, String address, String nip, String regon, String representative) {
    }

    public record Document(Variant variant, Employer employer, String employeeName, String positionName,
            String departmentName, LocalDateTime start, LocalDateTime end, String note, String decidedBy,
            LocalDateTime decidedAt) {

        int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }
    }

    public byte[] pdf(Document data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, 48, 48, 48, 48);
        boolean order = data.variant() == Variant.EMPLOYER_ORDER;
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle(order ? "Polecenie pracy w godzinach nadliczbowych"
                    : "Wniosek o pracę w godzinach nadliczbowych");
            document.open();

            PdfPTable header = new PdfPTable(new float[] { 1.3f, 1 });
            header.setWidthPercentage(100);
            header.addCell(plain(employerBlock(data.employer()), Element.ALIGN_LEFT));
            header.addCell(plain(new Phrase("Miejscowość, data: ...............................",
                    font(regular, 9.5f, INK)), Element.ALIGN_RIGHT));
            document.add(header);

            Paragraph title = new Paragraph(order ? "POLECENIE PRACY W GODZINACH NADLICZBOWYCH"
                    : "WNIOSEK O WYRAŻENIE ZGODY NA PRACĘ W GODZINACH NADLICZBOWYCH", font(bold, 13, INK));
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingBefore(28);
            title.setSpacingAfter(18);
            document.add(title);

            document.add(details(data));

            if (order) {
                orderBody(document, data);
            } else {
                requestBody(document, data);
            }
            if (data.note() != null) {
                Paragraph note = new Paragraph("Uwagi: " + data.note(), font(regular, 10, INK));
                note.setSpacingBefore(10);
                document.add(note);
            }
            document.add(signatures(order));

            Paragraph footer = new Paragraph("Odcinek przyjęty w karcie czasu pracy"
                    + (data.decidedBy() == null ? "" : " przez: " + data.decidedBy())
                    + (data.decidedAt() == null ? "" : ", " + STAMP.format(data.decidedAt()))
                    + ". Godziny według odbić karty.", font(regular, 8, MUTED));
            footer.setSpacingBefore(28);
            document.add(footer);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku nadgodzin", e);
        }
        return out.toByteArray();
    }

    private void orderBody(com.lowagie.text.Document document, Document data) throws DocumentException {
        document.add(text("Na podstawie art. 151 § 1 Kodeksu pracy polecam Pani/Panu wykonanie pracy w godzinach "
                + "nadliczbowych we wskazanym wyżej czasie z powodu:", 10.5f, 14));
        document.add(text(BOX + "szczególnych potrzeb pracodawcy (art. 151 § 1 pkt 2 KP),", 10.5f, 4));
        document.add(text(BOX + "konieczności prowadzenia akcji ratowniczej w celu ochrony życia lub zdrowia ludzkiego, "
                + "ochrony mienia lub środowiska albo usunięcia awarii (art. 151 § 1 pkt 1 KP).", 10.5f, 4));
        document.add(text("Opis potrzeby: ............................................................................"
                + "..........................................", 10.5f, 10));
        document.add(text("Za pracę w godzinach nadliczbowych przysługuje:", 10.5f, 12));
        document.add(text(BOX + "dodatek do wynagrodzenia (art. 151¹ § 1 KP: 100% w nocy, w niedziele i święta "
                + "niebędące dniami pracy oraz w dniu wolnym udzielonym za pracę w niedzielę lub święto; 50% w "
                + "pozostałe dni),", 10.5f, 4));
        document.add(text(BOX + "czas wolny w wymiarze o połowę wyższym niż liczba przepracowanych nadgodzin, "
                + "udzielony przez pracodawcę bez wniosku pracownika (art. 151² § 2 KP).", 10.5f, 4));
    }

    private void requestBody(com.lowagie.text.Document document, Document data) throws DocumentException {
        document.add(text("Zwracam się z prośbą o wyrażenie zgody na wykonanie pracy w godzinach nadliczbowych we "
                + "wskazanym wyżej czasie. Za pracę w tym czasie proszę o:", 10.5f, 14));
        document.add(text(BOX + "udzielenie czasu wolnego w tym samym wymiarze (art. 151² § 1 KP),", 10.5f, 4));
        document.add(text(BOX + "wypłatę dodatku do wynagrodzenia (art. 151¹ § 1 KP).", 10.5f, 4));
        document.add(text("Termin czasu wolnego (jeśli dotyczy): ...........................................",
                10.5f, 10));
        PdfPTable employeeSign = new PdfPTable(new float[] { 1, 1 });
        employeeSign.setWidthPercentage(100);
        employeeSign.setSpacingBefore(26);
        employeeSign.addCell(plain(new Phrase(""), Element.ALIGN_LEFT));
        employeeSign.addCell(signature("podpis pracownika"));
        document.add(employeeSign);

        Paragraph consent = new Paragraph("ZGODA PRACODAWCY", font(bold, 11, INK));
        consent.setSpacingBefore(22);
        document.add(consent);
        document.add(text("Wyrażam zgodę na pracę w godzinach nadliczbowych we wskazanym czasie z powodu szczególnych "
                + "potrzeb pracodawcy (art. 151 § 1 pkt 2 KP).", 10.5f, 6));
    }

    private PdfPTable details(Document data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 2 });
        table.setWidthPercentage(100);
        String hours = TIME.format(data.start()) + " – " + TIME.format(data.end())
                + (data.end().toLocalDate().isAfter(data.start().toLocalDate())
                        ? " (do " + DATE.format(data.end().toLocalDate()) + ")" : "");
        String[][] rows = {
                { "Pracownik", data.employeeName() },
                { "Stanowisko", data.positionName() + (data.departmentName() == null ? "" : ", dział "
                        + data.departmentName()) },
                { "Dzień", DATE.format(data.start().toLocalDate()) },
                { "Godziny", hours },
                { "Liczba godzin", hm(data.minutes()) } };
        for (String[] row : rows) {
            PdfPCell label = cell(new Phrase(row[0], font(bold, 10, INK)));
            label.setBackgroundColor(HEAD);
            table.addCell(label);
            table.addCell(cell(new Phrase(row[1], font(regular, 10, INK))));
        }
        table.setSpacingAfter(16);
        return table;
    }

    private PdfPTable signatures(boolean order) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 1 });
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        if (order) {
            table.addCell(signature("podpis pracodawcy lub osoby upoważnionej"));
            table.addCell(signature("przyjąłem do wiadomości, data i podpis pracownika"));
        } else {
            table.addCell(plain(new Phrase(""), Element.ALIGN_LEFT));
            table.addCell(signature("podpis pracodawcy lub osoby upoważnionej"));
        }
        table.setKeepTogether(true);
        return table;
    }

    private Phrase employerBlock(Employer employer) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(blankIfNull(employer.name()) + "\n", font(bold, 10, INK)));
        Font text = font(regular, 9, INK);
        if (employer.address() != null) {
            phrase.add(new Chunk(employer.address() + "\n", text));
        }
        if (employer.nip() != null) {
            phrase.add(new Chunk("NIP: " + employer.nip() + "\n", text));
        }
        if (employer.regon() != null) {
            phrase.add(new Chunk("REGON: " + employer.regon() + "\n", text));
        }
        phrase.add(new Chunk("(pracodawca)", font(regular, 8, MUTED)));
        return phrase;
    }

    private PdfPCell signature(String label) {
        PdfPCell cell = new PdfPCell(new Phrase("\n\n.................................................\n" + label,
                font(regular, 8.5f, MUTED)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private Paragraph text(String value, float size, float before) {
        Paragraph paragraph = new Paragraph(value, font(regular, size, INK));
        paragraph.setSpacingBefore(before);
        paragraph.setLeading(size * 1.45f);
        return paragraph;
    }

    private static PdfPCell plain(Phrase phrase, int align) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setPadding(6);
        return cell;
    }

    /** 75 → 1:15 h. */
    static String hm(int minutes) {
        return minutes / 60 + ":" + String.format("%02d", minutes % 60) + " h";
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
