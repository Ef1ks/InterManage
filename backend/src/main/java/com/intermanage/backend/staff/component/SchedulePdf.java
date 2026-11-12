package com.intermanage.backend.staff.component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.common.component.PdfPageFooter;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
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
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Grafik działu na miesiąc do powieszenia dla pracowników: A4 poziomo, wiersz = osoba, kolumna = dzień, w komórce
 * godzina rozpoczęcia nad godziną zakończenia albo skrót dnia wolnego lub nieobecności. Bez obsady, wymiaru, salda i
 * ostrzeżeń, bo to narzędzia układającego. Cały dział mieści się na jednej kartce, dopóki da się to zrobić czytelnie.
 */
@Component
public class SchedulePdf {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM");
    private static final Locale PL = Locale.of("pl");
    private static final String[] WEEKDAYS = { "pn", "wt", "śr", "cz", "pt", "so", "nd" };
    /** Poniżej tej skali tekst przestaje być czytelny z odległości, więc wydruk przechodzi na kolejne kartki. */
    private static final float MIN_SCALE = 0.7f;

    private static final Color INK = new Color(23, 23, 23);
    private static final Color MUTED = new Color(100, 100, 100);
    private static final Color RULE = new Color(190, 190, 190);
    private static final Color WEEK_RULE = new Color(60, 60, 60);
    private static final Color HEAD = new Color(236, 236, 238);
    private static final Color SECTION = new Color(222, 226, 233);
    private static final Color STRIPE = new Color(247, 247, 248);
    private static final Color SUNDAY = new Color(252, 238, 238);
    private static final Color CLOSED = new Color(225, 225, 225);
    private static final Color OFF = new Color(232, 240, 250);
    private static final Color ABSENCE = new Color(255, 243, 214);

    private final BaseFont regular;
    private final BaseFont bold;

    public SchedulePdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code holiday}: nazwa święta; {@code closed}: sklep zamknięty. */
    public record Day(LocalDate date, String holiday, boolean closed) {
    }

    public enum Kind {
        EMPTY, WORK, OFF, ABSENCE, CLOSED
    }

    /**
     * Zawartość komórki. Przy pracy {@code top} i {@code bottom} to początek i koniec, a {@code note} numer innego
     * sklepu albo skrót stanowiska dnia; przy dniu wolnym i nieobecności {@code top} to skrót.
     */
    public record Cell(Kind kind, String top, String bottom, String note) {

        public static final Cell EMPTY = new Cell(Kind.EMPTY, "", "", null);
    }

    /** {@code note}: stanowisko albo sklep pod nazwiskiem; {@code minutes}: suma godzin pracy w miesiącu. */
    public record Person(String name, String note, boolean civil, List<Cell> cells, int minutes) {
    }

    public record Section(String name, List<Person> people) {
    }

    /** {@code legend}: objaśnienia skrótów, które są na kartce, w kolejności wydruku. */
    public record Sheet(String departmentName, String storeLabel, YearMonth month, String versionLabel,
            LocalDateTime printedAt, List<Day> days, List<Section> sections, List<String> legend) {
    }

    public byte[] pdf(Sheet sheet) {
        float scale = 1;
        while (scale > MIN_SCALE && pages(render(sheet, scale)) > 1) {
            scale -= 0.05f;
        }
        return render(sheet, Math.max(scale, MIN_SCALE));
    }

    private static int pages(byte[] content) {
        try (PdfReader reader = new PdfReader(content)) {
            return reader.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] render(Sheet sheet, float k) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 22, 22, 16, 34);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, MUTED), "Grafik pracy · " + sheet.departmentName()
                    + " · " + monthName(sheet.month()) + " · " + sheet.storeLabel() + " · wydruk "
                    + STAMP.format(sheet.printedAt())));
            document.addTitle("Grafik " + sheet.departmentName() + " " + sheet.month());
            document.open();
            document.add(header(sheet, k));
            if (sheet.sections().isEmpty()) {
                document.add(new Paragraph("Ten grafik nie ma jeszcze nikogo z ułożonymi dniami.",
                        font(regular, 10, INK)));
            } else {
                document.add(table(sheet, k));
            }
            document.add(legend(sheet, k));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku grafiku", e);
        }
        return out.toByteArray();
    }

    private PdfPTable header(Sheet sheet, float k) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1.4f, 1 });
        table.setWidthPercentage(100);
        table.setSpacingAfter(4 * k);
        Phrase title = new Phrase();
        title.add(new Chunk("GRAFIK PRACY · " + sheet.departmentName().toUpperCase(PL) + "\n", font(bold, 15 * k, INK)));
        title.add(new Chunk(capitalize(monthName(sheet.month())), font(bold, 12 * k, INK)));
        PdfPCell left = new PdfPCell(title);
        left.setBorder(Rectangle.NO_BORDER);
        left.setLeading(0, 1.25f);
        table.addCell(left);
        Phrase meta = new Phrase();
        meta.add(new Chunk(sheet.storeLabel() + "\n", font(bold, 9 * k, INK)));
        meta.add(new Chunk(sheet.versionLabel(), font(regular, 8.5f * k, MUTED)));
        PdfPCell right = new PdfPCell(meta);
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setVerticalAlignment(Element.ALIGN_BOTTOM);
        right.setLeading(0, 1.3f);
        table.addCell(right);
        return table;
    }

    private PdfPTable table(Sheet sheet, float k) throws DocumentException {
        List<Day> days = sheet.days();
        float[] widths = new float[days.size() + 2];
        widths[0] = 6.2f;
        for (int i = 0; i < days.size(); i++) {
            widths[i + 1] = 1;
        }
        widths[widths.length - 1] = 1.5f;
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        PdfPCell name = cell(new Phrase("Pracownik", font(bold, 8 * k, INK)), Element.ALIGN_LEFT, k);
        name.setBackgroundColor(HEAD);
        table.addCell(name);
        for (Day day : days) {
            Phrase phrase = new Phrase();
            boolean red = ScheduleCalendar.isSunday(day.date()) || day.holiday() != null;
            Color ink = red ? new Color(170, 30, 30) : INK;
            phrase.add(new Chunk(day.date().getDayOfMonth() + "\n", font(bold, 9 * k, ink)));
            phrase.add(new Chunk(WEEKDAYS[day.date().getDayOfWeek().getValue() - 1], font(regular, 6.5f * k, ink)));
            PdfPCell cell = cell(phrase, Element.ALIGN_CENTER, k);
            cell.setLeading(0, 1.1f);
            cell.setBackgroundColor(day.closed() ? CLOSED : red ? SUNDAY : HEAD);
            weekRule(cell, day);
            table.addCell(cell);
        }
        PdfPCell sum = cell(new Phrase("Godz.", font(bold, 7.5f * k, INK)), Element.ALIGN_CENTER, k);
        sum.setBackgroundColor(HEAD);
        table.addCell(sum);

        boolean showSections = sheet.sections().size() > 1 || !sheet.sections().getFirst().name().isBlank();
        int stripe = 0;
        for (Section section : sheet.sections()) {
            if (showSections) {
                PdfPCell head = cell(new Phrase(section.name() + "  (" + section.people().stream()
                        .filter(person -> !person.civil()).count() + ")", font(bold, 8.5f * k, INK)),
                        Element.ALIGN_LEFT, k);
                head.setColspan(widths.length);
                head.setBackgroundColor(SECTION);
                head.setPaddingTop(1.5f * k);
                head.setPaddingBottom(3 * k);
                table.addCell(head);
            }
            for (Person person : section.people()) {
                // Wiersz zlecenia należy do osoby nad nim, więc ma to samo tło.
                if (!person.civil()) {
                    stripe++;
                }
                Color background = stripe % 2 == 0 ? STRIPE : Color.WHITE;
                table.addCell(personCell(person, background, k));
                for (int i = 0; i < days.size(); i++) {
                    table.addCell(dayCell(person.cells().get(i), days.get(i), background, k));
                }
                PdfPCell total = cell(new Phrase(hm(person.minutes()), font(bold, 8.5f * k, INK)),
                        Element.ALIGN_CENTER, k);
                total.setBackgroundColor(background);
                table.addCell(total);
            }
        }
        return table;
    }

    private PdfPCell personCell(Person person, Color background, float k) {
        Phrase phrase = new Phrase();
        if (person.civil()) {
            phrase.add(new Chunk("↳ zlecenie", font(regular, 7.5f * k, MUTED)));
        } else {
            phrase.add(new Chunk(person.name(), font(bold, 9 * k, INK)));
            if (person.note() != null && !person.note().isBlank()) {
                phrase.add(new Chunk("\n" + person.note(), font(regular, 6.5f * k, MUTED)));
            }
        }
        PdfPCell cell = cell(phrase, Element.ALIGN_LEFT, k);
        cell.setLeading(0, 1.15f);
        cell.setBackgroundColor(background);
        return cell;
    }

    private PdfPCell dayCell(Cell value, Day day, Color background, float k) {
        Phrase phrase = new Phrase();
        switch (value.kind()) {
            case WORK -> {
                phrase.add(new Chunk(value.top() + "\n", font(bold, 8.5f * k, INK)));
                phrase.add(new Chunk(value.bottom(), font(bold, 8.5f * k, INK)));
                if (value.note() != null) {
                    phrase.add(new Chunk("\n" + value.note(), font(regular, 5.5f * k, MUTED)));
                }
            }
            case OFF, ABSENCE -> phrase.add(new Chunk(value.top(), font(bold, 8 * k, INK)));
            case CLOSED, EMPTY -> phrase.add(new Chunk(" ", font(regular, 8 * k, INK)));
        }
        PdfPCell cell = cell(phrase, Element.ALIGN_CENTER, k);
        cell.setLeading(0, 1f);
        cell.setPaddingLeft(0.5f);
        cell.setPaddingRight(0.5f);
        cell.setBackgroundColor(switch (value.kind()) {
            case OFF -> OFF;
            case ABSENCE -> ABSENCE;
            default -> day.closed() ? CLOSED
                    : ScheduleCalendar.isSunday(day.date()) || day.holiday() != null ? SUNDAY : background;
        });
        weekRule(cell, day);
        return cell;
    }

    private Paragraph legend(Sheet sheet, float k) {
        // Legenda nie maleje razem z tabelą: ma się dać przeczytać.
        float size = Math.max(6.5f, 7.5f * k);
        Paragraph paragraph = new Paragraph();
        paragraph.setSpacingBefore(4 * k);
        paragraph.setLeading(size * 1.35f);
        paragraph.add(new Chunk("Jak czytać: ", font(bold, size, INK)));
        paragraph.add(new Chunk("w kratce godzina rozpoczęcia (u góry) i zakończenia pracy (na dole), pusta kratka to"
                + " dzień bez pracy. Godz. = suma godzin pracy w miesiącu. Czerwony dzień: niedziela albo święto,"
                + " szary: sklep zamknięty.", font(regular, size, INK)));
        for (String line : sheet.legend()) {
            paragraph.add(new Chunk("\n" + line, font(regular, size, INK)));
        }
        List<Day> holidays = sheet.days().stream().filter(day -> day.holiday() != null).toList();
        if (!holidays.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (Day day : holidays) {
                text.append(text.isEmpty() ? "" : "; ").append(DAY.format(day.date())).append(" ")
                        .append(day.holiday()).append(day.closed() ? " (sklep zamknięty)" : " (sklep czynny)");
            }
            paragraph.add(new Chunk("\nŚwięta: ", font(bold, size, INK)));
            paragraph.add(new Chunk(text.toString(), font(regular, size, INK)));
        }
        return paragraph;
    }

    /** Grubsza kreska przed poniedziałkiem: tygodnie widać od razu. */
    private static void weekRule(PdfPCell cell, Day day) {
        if (day.date().getDayOfWeek() == DayOfWeek.MONDAY && day.date().getDayOfMonth() > 1) {
            cell.setUseVariableBorders(true);
            cell.setBorderWidthLeft(1.2f);
            cell.setBorderColorLeft(WEEK_RULE);
        }
    }

    private static PdfPCell cell(Phrase phrase, int align, float k) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(0.8f * k);
        cell.setPaddingBottom(2.2f * k);
        cell.setPaddingLeft(3);
        cell.setPaddingRight(3);
        return cell;
    }

    /** 0 → pusto, 450 → 7:30, 480 → 8. */
    static String hm(int minutes) {
        if (minutes == 0) {
            return "";
        }
        return minutes % 60 == 0 ? String.valueOf(minutes / 60)
                : minutes / 60 + ":" + String.format("%02d", minutes % 60);
    }

    private static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, PL) + " " + month.getYear();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(PL) + text.substring(1);
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }
}
