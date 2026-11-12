package com.intermanage.backend.staff.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
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
 * Karta ewidencji czasu pracy (art. 149 KP i § 6 rozporządzenia o dokumentacji pracowniczej): jedna kartka A4 na
 * pracownika na miesiąc. Dzień po dniu czas pracy z odbić kart zestawionych z grafikiem, w tym w niedziele i święta, w
 * porze nocnej i w nadgodzinach dobowych, dni wolne z grafiku (W5, WN), urlopy i nieobecności. Dni, które się
 * jeszcze nie skończyły, są puste.
 */
@Component
public class TimeSheetPdf {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Locale PL = Locale.of("pl");

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(115, 115, 115);
    private static final Color RULE = new Color(200, 200, 200);
    private static final Color HEAD = new Color(244, 244, 245);
    private static final Color HOLIDAY = new Color(250, 236, 236);

    /** Skróty nieobecności i dni wolnych, jak w Grafiku. */
    public static final Map<String, String> SYMBOLS = Map.ofEntries(Map.entry("U", "urlop wypoczynkowy"),
            Map.entry("UŻ", "urlop na żądanie"), Map.entry("OD", "opieka nad dzieckiem (art. 188)"),
            Map.entry("SW", "siła wyższa (art. 148¹)"), Map.entry("UO", "urlop opiekuńczy (art. 173¹)"),
            Map.entry("TR", "turnus rehabilitacyjny"), Map.entry("OK", "urlop okolicznościowy"),
            Map.entry("UB", "urlop bezpłatny"), Map.entry("N", "nieobecność"),
            Map.entry("W", "dzień wolny"),
            Map.entry("W5", "wolne z tytułu pięciodniowego tygodnia pracy"),
            Map.entry("WN", "wolne za pracę w niedzielę lub święto"));

    private final BaseFont regular;
    private final BaseFont bold;

    public TimeSheetPdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    public record Employer(String name, String address, String nip) {
    }

    /**
     * {@code counted}: dzień zakończony, więc ma liczby. {@code mark}: skrót nieobecności albo dnia wolnego
     * ({@link #SYMBOLS}). Minuty w całych minutach.
     */
    public record Day(LocalDate date, String holiday, LocalDateTime plannedStart, LocalDateTime plannedEnd,
            LocalDateTime firstIn, LocalDateTime lastOut, boolean counted, int worked, int dailyOvertime, int night,
            int sundayHoliday, String mark, String note) {
    }

    public record Totals(int worked, int dailyOvertime, int night, int sundayHoliday, int workDays,
            Map<String, Integer> markDays, int shortfall, int pending, int outsideAccepted) {
    }

    public record Person(String name, Integer rcpNumber, String positionName, String departmentName, List<Day> days,
            Totals totals) {
    }

    public record Sheet(Employer employer, String storeLabel, YearMonth month, LocalDateTime printedAt,
            List<Person> people) {
    }

    /** Każda karta osobno dostaje skalę, przy której mieści się na jednej kartce (długie uwagi łamią wiersze). */
    public byte[] pdf(Sheet sheet) {
        List<Float> scales = sheet.people().stream().map(person -> fit(sheet, person)).toList();
        return render(sheet, sheet.people(), scales);
    }

    private float fit(Sheet sheet, Person person) {
        float scale = 1;
        while (scale > 0.55f && pages(render(sheet, List.of(person), List.of(scale))) > 1) {
            scale -= 0.05f;
        }
        return scale;
    }

    private static int pages(byte[] content) {
        try (com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(content)) {
            return reader.getNumberOfPages();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private byte[] render(Sheet sheet, List<Person> people, List<Float> scales) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, 32, 32, 30, 44);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7.5f, MUTED),
                    "Karta ewidencji czasu pracy · " + monthName(sheet.month()) + " · " + sheet.storeLabel()
                            + " · wydruk " + STAMP.format(sheet.printedAt())));
            document.addTitle("Karta ewidencji czasu pracy " + sheet.month());
            document.open();
            for (int i = 0; i < people.size(); i++) {
                if (i > 0) {
                    document.newPage();
                }
                page(document, sheet, people.get(i), scales.get(i));
            }
            if (people.isEmpty()) {
                document.add(new Paragraph("Brak pracowników z umową o pracę w tym miesiącu.",
                        font(regular, 10, INK)));
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć karty ewidencji czasu pracy", e);
        }
        return out.toByteArray();
    }

    private void page(com.lowagie.text.Document document, Sheet sheet, Person person, float k)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] { 1.2f, 1 });
        header.setWidthPercentage(100);
        header.addCell(plain(employerBlock(sheet.employer()), Element.ALIGN_LEFT));
        Phrase who = new Phrase();
        who.add(new Chunk(person.name() + "\n", font(bold, 11, INK)));
        who.add(new Chunk(person.positionName()
                + (person.departmentName() == null ? "" : ", dział " + person.departmentName()) + "\n",
                font(regular, 8.5f, INK)));
        if (person.rcpNumber() != null) {
            who.add(new Chunk("nr karty RCP: " + person.rcpNumber() + "\n", font(regular, 8.5f, INK)));
        }
        who.add(new Chunk("(pracownik)", font(regular, 7.5f, MUTED)));
        header.addCell(plain(who, Element.ALIGN_RIGHT));
        document.add(header);

        Paragraph title = new Paragraph("KARTA EWIDENCJI CZASU PRACY · " + monthName(sheet.month()).toUpperCase(PL),
                font(bold, 12, INK));
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(10);
        title.setSpacingAfter(8);
        document.add(title);

        document.add(days(person, k));
        document.add(totals(person.totals(), k));
        document.add(legend(person, k));
        document.add(signatures(k));
    }

    private PdfPTable days(Person person, float k) throws DocumentException {
        String[] heads = { "Dzień", "Grafik", "Wejście", "Wyjście", "Czas pracy", "Nadgodziny dobowe", "Pora nocna",
                "Niedz. / święto", "Wolne / nieob.", "Uwagi" };
        PdfPTable table = new PdfPTable(new float[] { 1.15f, 1.25f, 0.75f, 0.75f, 0.8f, 0.8f, 0.75f, 0.8f, 0.8f, 2.6f });
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String head : heads) {
            PdfPCell cell = cell(head, bold, 7 * k, Element.ALIGN_CENTER, k);
            cell.setBackgroundColor(HEAD);
            table.addCell(cell);
        }
        for (Day day : person.days()) {
            boolean red = day.holiday() != null || ScheduleCalendar.isSunday(day.date());
            String label = day.date().getDayOfMonth() + " "
                    + day.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, PL);
            String planned = day.plannedStart() == null ? "" : time(day.plannedStart()) + "–" + time(day.plannedEnd());
            String[] values = { label, planned, time(day.firstIn()), time(day.lastOut()),
                    day.counted() ? hm(day.worked()) : "", day.counted() ? hm(day.dailyOvertime()) : "",
                    day.counted() ? hm(day.night()) : "", day.counted() ? hm(day.sundayHoliday()) : "",
                    day.mark() == null ? "" : day.mark(), day.note() == null ? "" : day.note() };
            for (int i = 0; i < values.length; i++) {
                PdfPCell cell = cell(values[i], i == 8 || (i == 4 && day.counted()) ? bold : regular,
                        (i == 9 ? 6.5f : 7.5f) * k, i == 0 || i == 9 ? Element.ALIGN_LEFT : Element.ALIGN_CENTER, k);
                if (red) {
                    cell.setBackgroundColor(HOLIDAY);
                }
                table.addCell(cell);
            }
        }
        Totals totals = person.totals();
        String[] sum = { "Razem", "", "", "", hm(totals.worked()), hm(totals.dailyOvertime()), hm(totals.night()),
                hm(totals.sundayHoliday()), "", "" };
        for (int i = 0; i < sum.length; i++) {
            PdfPCell cell = cell(sum[i], bold, 7.5f * k, i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_CENTER, k);
            cell.setBackgroundColor(HEAD);
            table.addCell(cell);
        }
        return table;
    }

    private PdfPTable totals(Totals totals, float k) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 1, 1, 1 });
        table.setWidthPercentage(100);
        table.setSpacingBefore(8 * k);
        StringBuilder leaves = new StringBuilder();
        totals.markDays().forEach((mark, count) -> leaves.append(leaves.isEmpty() ? "" : ", ").append(mark)
                .append(": ").append(count));
        String[][] items = { { "Dni pracy", String.valueOf(totals.workDays()) },
                { "Wolne i nieobecności (dni)", leaves.isEmpty() ? "brak" : leaves.toString() },
                { "Przyjęte poza grafikiem", hm(totals.outsideAccepted()) },
                { "Braki nieusprawiedliwione", hm(totals.shortfall()) } };
        for (String[] item : items) {
            Phrase phrase = new Phrase();
            phrase.add(new Chunk(item[0] + "\n", font(regular, 7, MUTED)));
            phrase.add(new Chunk(item[1], font(bold, 9, INK)));
            PdfPCell cell = new PdfPCell(phrase);
            cell.setBorderColor(RULE);
            cell.setPadding(5 * k);
            table.addCell(cell);
        }
        if (totals.pending() > 0) {
            PdfPCell cell = new PdfPCell(new Phrase("Czas poza grafikiem bez decyzji kierownika: " + hm(totals.pending())
                    + " (nie wchodzi do czasu pracy, do rozstrzygnięcia w Czasie pracy).", font(bold, 7.5f * k, INK)));
            cell.setColspan(4);
            cell.setBorderColor(RULE);
            cell.setPadding(5 * k);
            table.addCell(cell);
        }
        return table;
    }

    private Paragraph legend(Person person, float k) {
        StringBuilder text = new StringBuilder("Oznaczenia: ");
        boolean first = true;
        for (String mark : person.totals().markDays().keySet()) {
            text.append(first ? "" : "; ").append(mark).append(" – ").append(SYMBOLS.getOrDefault(mark, mark));
            first = false;
        }
        if (first) {
            text.append("brak dni wolnych i nieobecności");
        }
        text.append(". Czas pracy z odbić karty zestawionych z grafikiem, bez przerw niepłatnych, z nadgodzinami przyjętymi"
                + " przez kierownika. Nadgodziny dobowe: ponad dobowy limit (8 h, przy znacznym stopniu "
                + "niepełnosprawności bez zgody lekarza 7 h). Pora nocna 22–6. Tło: niedziela albo święto.");
        Paragraph paragraph = new Paragraph(text.toString(), font(regular, 6.5f * k, MUTED));
        paragraph.setSpacingBefore(6 * k);
        paragraph.setLeading(9 * k);
        return paragraph;
    }

    private PdfPTable signatures(float k) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1, 1 });
        table.setWidthPercentage(100);
        table.setSpacingBefore(14 * k);
        table.addCell(signature("data i podpis pracodawcy lub osoby upoważnionej"));
        table.addCell(signature("zapoznałem(-am) się, data i podpis pracownika"));
        table.setKeepTogether(true);
        return table;
    }

    private Phrase employerBlock(Employer employer) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk((employer.name() == null ? "" : employer.name()) + "\n", font(bold, 9, INK)));
        Font text = font(regular, 8, INK);
        if (employer.address() != null) {
            phrase.add(new Chunk(employer.address() + "\n", text));
        }
        if (employer.nip() != null) {
            phrase.add(new Chunk("NIP: " + employer.nip() + "\n", text));
        }
        phrase.add(new Chunk("(pracodawca)", font(regular, 7.5f, MUTED)));
        return phrase;
    }

    private PdfPCell signature(String label) {
        PdfPCell cell = new PdfPCell(new Phrase("\n.................................................\n" + label,
                font(regular, 7.5f, MUTED)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private PdfPCell cell(String text, BaseFont base, float size, int align, float k) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(base, size, INK)));
        cell.setBorderColor(RULE);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(1.5f * k);
        cell.setPaddingBottom(2.5f * k);
        cell.setPaddingLeft(2.5f);
        cell.setPaddingRight(2.5f);
        return cell;
    }

    private static PdfPCell plain(Phrase phrase, int align) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static String time(LocalDateTime value) {
        return value == null ? "" : TIME.format(LocalTime.from(value));
    }

    /** 0 → pusto (karta czytelniejsza), 75 → 1:15. */
    static String hm(int minutes) {
        return minutes == 0 ? "" : minutes / 60 + ":" + String.format("%02d", minutes % 60);
    }

    private static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, PL) + " " + month.getYear();
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
