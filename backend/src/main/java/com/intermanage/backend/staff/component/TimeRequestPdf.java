package com.intermanage.backend.staff.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.staff.dto.TimeRequestResponse;
import com.intermanage.backend.staff.dto.TimeSettingsResponse;
import com.intermanage.backend.staff.entity.StaffTimeRequestEvent;
import com.intermanage.backend.staff.entity.TimeRequestKind;
import com.intermanage.backend.staff.entity.TimeRequestStatus;
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
 * Wniosek pracownika do akt (A4 pionowo, jeden na kartce): dane, treść zależna od rodzaju, decyzje kierownika i kadr z
 * podpisami i historia kroków z aplikacji. Wniosek złożony w aplikacji nie ma podpisu pracownika, więc jest miejsce
 * na podpis przy wydruku.
 */
@Component
public class TimeRequestPdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(200, 200, 200);
    private static final Color HEAD = new Color(244, 244, 245);

    private final BaseFont regular;
    private final BaseFont bold;

    public TimeRequestPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public record Event(StaffTimeRequestEvent.Action action, Instant at, String by, String note) {
    }

    public record Request(TimeRequestKind kind, String employeeName, Integer rcpNumber, String departmentName,
            String positionName, LocalDate workDate, LocalDate dateTo, LocalDateTime start, LocalDateTime end,
            String reason, TimeRequestStatus status, Instant createdAt, String createdBy, boolean byEmployee,
            TimeRequestResponse.Step manager, TimeRequestResponse.Step hr, boolean needsHr, List<Event> history) {
    }

    public byte[] pdf(TimeSettingsResponse.Employer employer, List<Request> requests) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, 48, 48, 44, 44);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle(requests.size() == 1 ? "Wniosek pracownika" : "Wnioski pracowników");
            document.open();
            boolean first = true;
            for (Request request : requests) {
                if (!first) {
                    document.newPage();
                }
                first = false;
                page(document, employer, request);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku wniosku", e);
        }
        return out.toByteArray();
    }

    private void page(com.lowagie.text.Document document, TimeSettingsResponse.Employer employer, Request data)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] { 1.3f, 1 });
        header.setWidthPercentage(100);
        header.addCell(plain(employerBlock(employer), Element.ALIGN_LEFT));
        header.addCell(plain(new Phrase("Data złożenia: " + DATE.format(local(data.createdAt()).toLocalDate()),
                font(regular, 9.5f, INK)), Element.ALIGN_RIGHT));
        document.add(header);

        Paragraph title = new Paragraph("WNIOSEK PRACOWNIKA", font(bold, 13, INK));
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(22);
        document.add(title);
        Paragraph subtitle = new Paragraph(data.kind().label(), font(regular, 10.5f, INK));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(14);
        document.add(subtitle);

        document.add(details(data));
        document.add(text(body(data), 10.5f, 12));
        if (data.reason() != null) {
            document.add(text("Uzasadnienie: " + data.reason(), 10.5f, 8));
        }
        String submitted = data.byEmployee() ? "Złożony przez pracownika w aplikacji, "
                : "Wpisany" + (data.createdBy() == null ? "" : " przez: " + data.createdBy()) + ", ";
        document.add(text(submitted + STAMP.format(local(data.createdAt())) + ".", 9, 6));

        PdfPTable employeeSign = new PdfPTable(new float[] { 1, 1 });
        employeeSign.setWidthPercentage(100);
        employeeSign.setSpacingBefore(18);
        employeeSign.addCell(plain(new Phrase(""), Element.ALIGN_LEFT));
        employeeSign.addCell(signature("podpis pracownika"));
        document.add(employeeSign);

        Paragraph decisions = new Paragraph("DECYZJA", font(bold, 11, INK));
        decisions.setSpacingBefore(18);
        document.add(decisions);
        document.add(decisionsTable(data));

        PdfPTable signs = new PdfPTable(data.needsHr() ? new float[] { 1, 1 } : new float[] { 1 });
        signs.setWidthPercentage(data.needsHr() ? 100 : 50);
        signs.setHorizontalAlignment(Element.ALIGN_RIGHT);
        signs.setSpacingBefore(18);
        signs.addCell(signature("podpis kierownika"));
        if (data.needsHr()) {
            signs.addCell(signature("podpis kadr"));
        }
        signs.setKeepTogether(true);
        document.add(signs);

        if (!data.history().isEmpty()) {
            Paragraph history = new Paragraph("Historia w aplikacji", font(bold, 9, MUTED));
            history.setSpacingBefore(18);
            document.add(history);
            for (Event event : data.history()) {
                document.add(text(STAMP.format(local(event.at())) + "  " + action(event.action())
                        + (event.by() == null ? "" : " · " + event.by())
                        + (event.note() == null ? "" : " · " + event.note()), 8, 2));
            }
        }
    }

    /** Treść wniosku w pierwszej osobie, zależna od rodzaju. */
    private static String body(Request data) {
        return switch (data.kind()) {
            case OVERTIME -> "Proszę o zgodę na zaliczenie pracy wykonanej we wskazanym czasie poza rozkładem do pracy "
                    + "w godzinach nadliczbowych (art. 151 § 1 KP).";
            case WORK_OFF -> "We wskazanym czasie odpracowałam/odpracowałem wcześniejsze zwolnienie od pracy na sprawy "
                    + "osobiste. Czas odpracowania nie jest pracą w godzinach nadliczbowych (art. 151²¹ § 2 KP).";
            case PRIVATE_EXIT -> "Proszę o zwolnienie od pracy we wskazanym czasie w celu załatwienia spraw osobistych. "
                    + "Zobowiązuję się odpracować ten czas (art. 151²¹ § 1 KP).";
            case LATE_OR_EARLY -> "Proszę o usprawiedliwienie nieobecności w pracy we wskazanym czasie (spóźnienie albo "
                    + "wcześniejsze wyjście) z przyczyny podanej niżej. Za ten czas nie przysługuje wynagrodzenie.";
            case FORCE_MAJEURE -> "Proszę o zwolnienie od pracy we wskazanym czasie z powodu działania siły wyższej w "
                    + "pilnych sprawach rodzinnych spowodowanych chorobą lub wypadkiem (art. 148¹ KP). Za ten czas "
                    + "przysługuje połowa wynagrodzenia.";
            case CHILD_CARE -> "Proszę o zwolnienie od pracy we wskazanym czasie na opiekę nad dzieckiem w wieku do 14 "
                    + "lat (art. 188 KP), z zachowaniem prawa do wynagrodzenia.";
            case OTHER_PAID -> "Proszę o usprawiedliwienie nieobecności we wskazanym czasie z zachowaniem prawa do "
                    + "wynagrodzenia (przyczyna i dokument potwierdzający niżej).";
            case SICK_LEAVE -> "Zawiadamiam o niezdolności do pracy we wskazanym okresie. Zwolnienie lekarskie (e-ZLA) "
                    + "pracodawca otrzymuje z ZUS.";
        };
    }

    private PdfPTable details(Request data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 2 });
        table.setWidthPercentage(100);
        String position = data.positionName() + (data.departmentName() == null ? "" : ", dział "
                + data.departmentName());
        String[][] rows;
        if (data.start() == null) {
            long days = ChronoUnit.DAYS.between(data.workDate(), data.dateTo()) + 1;
            rows = new String[][] {
                    { "Pracownik", data.employeeName() + rcp(data) },
                    { "Stanowisko", position },
                    { "Okres", DATE.format(data.workDate()) + " – " + DATE.format(data.dateTo()) + " (" + days
                            + (days == 1 ? " dzień)" : " dni)") } };
        } else {
            int minutes = (int) Duration.between(data.start(), data.end()).toMinutes();
            String hours = TIME.format(data.start()) + " – " + TIME.format(data.end())
                    + (data.end().toLocalDate().isAfter(data.start().toLocalDate())
                            ? " (do " + DATE.format(data.end().toLocalDate()) + ")" : "");
            rows = new String[][] {
                    { "Pracownik", data.employeeName() + rcp(data) },
                    { "Stanowisko", position },
                    { "Dzień", DATE.format(data.workDate()) },
                    { "Godziny", hours },
                    { "Czas", minutes / 60 + ":" + String.format("%02d", minutes % 60) + " h" } };
        }
        for (String[] row : rows) {
            PdfPCell label = cell(new Phrase(row[0], font(bold, 10, INK)));
            label.setBackgroundColor(HEAD);
            table.addCell(label);
            table.addCell(cell(new Phrase(row[1], font(regular, 10, INK))));
        }
        return table;
    }

    private PdfPTable decisionsTable(Request data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 2 });
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        // Odrzucił ten, kto podpisał ostatni krok: kadry, a bez ich kroku kierownik.
        boolean rejected = data.status() == TimeRequestStatus.REJECTED;
        addStep(table, "Kierownik", data.manager(), rejected && data.hr() == null);
        if (data.needsHr()) {
            addStep(table, "Kadry", data.hr(), rejected);
        }
        PdfPCell label = cell(new Phrase("Stan", font(bold, 10, INK)));
        label.setBackgroundColor(HEAD);
        table.addCell(label);
        table.addCell(cell(new Phrase(status(data.status()), font(bold, 10, INK))));
        return table;
    }

    private void addStep(PdfPTable table, String who, TimeRequestResponse.Step step, boolean rejected) {
        PdfPCell label = cell(new Phrase(who, font(bold, 10, INK)));
        label.setBackgroundColor(HEAD);
        table.addCell(label);
        String value = step == null ? "☐ akceptuję    ☐ nie akceptuję"
                : (rejected ? "Nie zaakceptowano" : "Zaakceptowano") + (step.by() == null ? "" : ": " + step.by())
                        + ", " + STAMP.format(local(step.at()))
                        + (step.note() == null ? "" : "\nUwaga: " + step.note());
        table.addCell(cell(new Phrase(value, font(regular, 10, INK))));
    }

    private static String status(TimeRequestStatus status) {
        return switch (status) {
            case PENDING -> "czeka na kierownika";
            case MANAGER_APPROVED -> "zaakceptowany przez kierownika, czeka na kadry";
            case APPROVED -> "zatwierdzony";
            case REJECTED -> "odrzucony";
            case CANCELLED -> "wycofany przez pracownika";
        };
    }

    private static String action(StaffTimeRequestEvent.Action action) {
        return switch (action) {
            case SUBMITTED -> "złożony";
            case MANAGER_APPROVED -> "kierownik zaakceptował";
            case MANAGER_REJECTED -> "kierownik odrzucił";
            case HR_APPROVED -> "kadry zatwierdziły";
            case HR_REJECTED -> "kadry odrzuciły";
            case CANCELLED -> "pracownik wycofał";
            case UNDONE -> "cofnięto ostatni krok";
        };
    }

    private static String rcp(Request data) {
        return data.rcpNumber() == null ? "" : " (nr RCP " + data.rcpNumber() + ")";
    }

    private Phrase employerBlock(TimeSettingsResponse.Employer employer) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk((employer.name() == null ? "" : employer.name()) + "\n", font(bold, 10, INK)));
        Font text = font(regular, 9, INK);
        if (employer.address() != null) {
            phrase.add(new Chunk(employer.address() + "\n", text));
        }
        if (employer.nip() != null) {
            phrase.add(new Chunk("NIP: " + employer.nip() + "\n", text));
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

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, StoreTime.ZONE);
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
