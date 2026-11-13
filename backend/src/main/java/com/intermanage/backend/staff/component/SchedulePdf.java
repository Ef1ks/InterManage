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
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Grafik działu na miesiąc do powieszenia dla pracowników: A4 poziomo, wiersz = osoba, kolumna = dzień. Bez obsady,
 * wymiaru, salda i ostrzeżeń, bo to narzędzia układającego. Cały dział mieści się na jednej kartce, dopóki da się to
 * zrobić czytelnie.
 * <p>
 * Zawartość komórek dni rysuje {@link PdfPCellEvent} w stałych miejscach (początek, kreska, koniec, znaczek na dole),
 * a tekst za szeroki na kolumnę jest zmniejszany, nigdy łamany: łamanie „13:30” w wąskiej kolumnie dawało nachodzące
 * na siebie cyfry. Minuty są małymi cyframi w indeksie górnym (13³⁰), jak w rozkładach jazdy, żeby godzina mieściła
 * się w kolumnie dużą czcionką.
 */
@Component
public class SchedulePdf {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM");
    private static final Locale PL = Locale.of("pl");
    private static final String[] WEEKDAYS = { "pn", "wt", "śr", "cz", "pt", "so", "nd" };
    /** Poniżej tej skali tekst przestaje być czytelny z odległości, więc wydruk przechodzi na kolejne kartki. */
    private static final float MIN_SCALE = 0.56f;
    private static final float MAX_SCALE = 1.3f;
    /** Odstęp godzin od krawędzi kolumny. */
    private static final float CELL_PADDING = 4.5f;
    /** Marginesy ok. 8 mm, dół większy na stopkę. */
    private static final float MARGIN = 23;
    private static final float MARGIN_BOTTOM = 32;
    private static final float SUM_WIDTH = 30;

    private static final Color INK = new Color(15, 23, 42);
    private static final Color MUTED = new Color(71, 85, 105);
    private static final Color SOFT = new Color(100, 116, 139);
    private static final Color RULE = new Color(203, 213, 225);
    private static final Color WEEK_RULE = new Color(30, 41, 59);
    private static final Color HEAD = new Color(241, 245, 249);
    private static final Color SECTION = new Color(226, 232, 240);
    private static final Color STRIPE = new Color(248, 250, 252);
    private static final Color SUNDAY = new Color(254, 236, 236);
    private static final Color SUNDAY_HEAD = new Color(252, 214, 214);
    private static final Color SUNDAY_INK = new Color(176, 24, 24);
    private static final Color CLOSED = new Color(214, 219, 226);
    private static final Color ABSENCE = new Color(254, 238, 196);
    private static final Color BADGE = new Color(226, 232, 240);
    private static final Color WARN = new Color(180, 83, 9);
    private static final Color WARN_FILL = new Color(254, 243, 199);

    private final BaseFont regular;
    private final BaseFont bold;

    public SchedulePdf(PdfFonts fonts) {
        this.regular = fonts.regular();
        this.bold = fonts.bold();
    }

    /** {@code holiday}: nazwa święta; {@code closed}: sklep zamknięty. */
    public record Day(LocalDate date, String holiday, boolean closed) {

        boolean red() {
            return ScheduleCalendar.isSunday(date) || holiday != null;
        }
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

        public static Cell work(String start, String end, String note) {
            return new Cell(Kind.WORK, start, end, note);
        }

        boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    /** {@code note}: sklep pod nazwiskiem gościa; {@code minutes}: suma godzin pracy w miesiącu. */
    public record Person(String name, String note, boolean civil, List<Cell> cells, int minutes) {

        boolean hasNotes() {
            return cells.stream().anyMatch(Cell::hasNote);
        }
    }

    public record Section(String name, List<Person> people) {
    }

    /** Pozycja legendy: przykładowa komórka narysowana jak w tabeli i jej opis. */
    public record LegendItem(Cell sample, String text) {
    }

    /**
     * {@code draft}: grafik jeszcze nie przekazany pracownikom (wersja w ramce ostrzegawczej); {@code legend}: tylko
     * to, co jest na kartce, w kolejności wydruku.
     */
    public record Sheet(String departmentName, String storeLabel, YearMonth month, String versionLabel, boolean draft,
            LocalDateTime printedAt, List<Day> days, List<Section> sections, List<LegendItem> legend) {
    }

    public byte[] pdf(Sheet sheet) {
        // Mały dział dostaje wyższe wiersze i większe litery, duży schodzi do MIN_SCALE.
        float scale = MAX_SCALE;
        while (scale > MIN_SCALE && pages(render(sheet, scale)) > 1) {
            scale -= 0.04f;
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
        Document document = new Document(PageSize.A4.rotate(), MARGIN, MARGIN, MARGIN, MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageFooter(font(regular, 7, SOFT), "Grafik pracy · " + sheet.departmentName()
                    + " · " + monthName(sheet.month()) + " · " + sheet.storeLabel() + " · wydruk "
                    + STAMP.format(sheet.printedAt())));
            document.addTitle("Grafik " + sheet.departmentName() + " " + sheet.month());
            document.open();
            Layout layout = layout(sheet, document.right() - document.left(), k);
            document.add(header(sheet, Math.min(k, 1)));
            if (sheet.sections().isEmpty()) {
                document.add(new Paragraph("Ten grafik nie ma jeszcze nikogo z ułożonymi dniami.",
                        font(regular, 10, INK)));
            } else {
                document.add(table(sheet, layout));
            }
            document.add(legend(sheet, layout));
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Nie udało się utworzyć wydruku grafiku", e);
        }
        return out.toByteArray();
    }

    /**
     * Wymiary wspólne dla całej kartki: szerokości kolumn, wielkość godzin (jedna dla wszystkich komórek, żeby kolumny
     * wyglądały równo) i wysokości wierszy.
     */
    private record Layout(float k, float width, float nameWidth, float dayWidth, float timeSize, float endSize,
            float rowHeight, float noteRowHeight) {
    }

    private Layout layout(Sheet sheet, float width, float k) {
        float nameSize = name(k);
        float longest = 0;
        for (Section section : sheet.sections()) {
            for (Person person : section.people()) {
                longest = Math.max(longest, bold.getWidthPoint(person.name(), nameSize));
            }
        }
        float nameWidth = Math.clamp(longest + 10, 78, 150);
        float dayWidth = (width - nameWidth - SUM_WIDTH) / Math.max(sheet.days().size(), 1);
        // Najszersza godzina, jaka może się trafić: dwie cyfry i małe minuty.
        float fit = (dayWidth - CELL_PADDING) / timeWidth(bold, "22:30", 1);
        float timeSize = Math.min(10.5f * k, fit);
        float endSize = timeSize * 0.92f;
        float rowHeight = cap(timeSize) + gap(k) + cap(endSize) + 4.5f * k;
        float noteRowHeight = rowHeight + noteGap(k) + badgeHeight(k);
        return new Layout(k, width, nameWidth, dayWidth, timeSize, endSize, rowHeight, noteRowHeight);
    }

    // --- nagłówek

    private PdfPTable header(Sheet sheet, float k) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] { 1.3f, 1 });
        table.setWidthPercentage(100);
        table.setSpacingAfter(6 * k);

        Phrase title = new Phrase();
        title.add(new Chunk("GRAFIK PRACY – " + sheet.departmentName().toUpperCase(PL) + "\n",
                font(bold, 11 * k, MUTED)));
        title.add(new Chunk(capitalize(monthName(sheet.month())), font(bold, 20 * k, INK)));
        PdfPCell left = new PdfPCell(title);
        left.setBorder(Rectangle.NO_BORDER);
        left.setPadding(0);
        left.setLeading(0, 1.15f);
        table.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(0);
        right.setVerticalAlignment(Element.ALIGN_BOTTOM);
        Paragraph store = new Paragraph(sheet.storeLabel(), font(bold, 9.5f * k, INK));
        store.setAlignment(Element.ALIGN_RIGHT);
        store.setSpacingAfter(3 * k);
        right.addElement(store);
        right.addElement(status(sheet, k));
        table.addCell(right);
        return table;
    }

    /** Wersja w ramce: wersja robocza na bursztynowo, żeby nikt nie wziął jej za obowiązujący grafik. */
    private PdfPTable status(Sheet sheet, float k) {
        Font text = font(bold, 8.5f * k, sheet.draft() ? WARN : MUTED);
        String label = sheet.draft() ? "⚠ " + sheet.versionLabel() : sheet.versionLabel();
        PdfPTable badge = new PdfPTable(1);
        badge.setTotalWidth(text.getBaseFont().getWidthPoint(label, text.getSize()) + 14 * k);
        badge.setLockedWidth(true);
        badge.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell cell = new PdfPCell(new Phrase(label, text));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPaddingTop(2 * k);
        cell.setPaddingBottom(4 * k);
        cell.setBorderWidth(sheet.draft() ? 1.2f : 0.6f);
        cell.setBorderColor(sheet.draft() ? WARN : RULE);
        cell.setBackgroundColor(sheet.draft() ? WARN_FILL : Color.WHITE);
        badge.addCell(cell);
        return badge;
    }

    // --- tabela

    private PdfPTable table(Sheet sheet, Layout l) throws DocumentException {
        List<Day> days = sheet.days();
        float[] widths = new float[days.size() + 2];
        widths[0] = l.nameWidth();
        for (int i = 0; i < days.size(); i++) {
            widths[i + 1] = l.dayWidth();
        }
        widths[widths.length - 1] = SUM_WIDTH;
        PdfPTable table = new PdfPTable(widths);
        table.setTotalWidth(l.width());
        table.setLockedWidth(true);
        table.setHeaderRows(1);

        float k = l.k();
        PdfPCell name = textCell(new Phrase("Pracownik", font(bold, 8 * k, MUTED)), Element.ALIGN_LEFT, HEAD);
        name.setPaddingLeft(4);
        table.addCell(name);
        for (Day day : days) {
            Color ink = day.red() ? SUNDAY_INK : INK;
            Phrase phrase = new Phrase();
            // Numer dnia nie może się złamać w wąskiej kolumnie („1 0”), więc rośnie tylko do jej szerokości.
            float number = Math.min(10 * Math.min(k, 1.1f), (l.dayWidth() - 3) / bold.getWidthPoint("31", 1));
            phrase.add(new Chunk(day.date().getDayOfMonth() + "\n", font(bold, number, ink)));
            phrase.add(new Chunk(WEEKDAYS[day.date().getDayOfWeek().getValue() - 1],
                    font(regular, 6.5f * Math.min(k, 1.1f), ink)));
            PdfPCell cell = textCell(phrase, Element.ALIGN_CENTER, day.closed() ? CLOSED
                    : day.red() ? SUNDAY_HEAD : HEAD);
            cell.setLeading(0, 1.1f);
            cell.setPaddingBottom(2.5f * k);
            weekRule(cell, day);
            table.addCell(cell);
        }
        table.addCell(textCell(new Phrase("Godz.", font(bold, 7.5f * Math.min(k, 1), MUTED)), Element.ALIGN_CENTER,
                HEAD));
        // Jedna wielkość sum w całej kolumnie, dobrana do najdłuższej możliwej („188:45”).
        float sumSize = Math.min(9 * Math.min(k, 1.1f), (SUM_WIDTH - 4) / bold.getWidthPoint("888:88", 1));

        boolean showSections = sheet.sections().size() > 1 || !sheet.sections().getFirst().name().isBlank();
        int stripe = 0;
        for (Section section : sheet.sections()) {
            if (showSections) {
                long count = section.people().stream().filter(person -> !person.civil()).count();
                PdfPCell head = textCell(new Phrase(section.name().toUpperCase(PL) + "  (" + count + ")",
                        font(bold, 8 * k, INK)), Element.ALIGN_LEFT, SECTION);
                head.setColspan(widths.length);
                head.setPaddingLeft(4);
                head.setPaddingTop(1.5f * k);
                head.setPaddingBottom(3 * k);
                head.setBorderColor(new Color(148, 163, 184));
                table.addCell(head);
            }
            for (Person person : section.people()) {
                // Wiersz zlecenia należy do osoby nad nim, więc ma to samo tło.
                if (!person.civil()) {
                    stripe++;
                }
                Color background = stripe % 2 == 0 ? STRIPE : Color.WHITE;
                float height = person.hasNotes() ? l.noteRowHeight() : l.rowHeight();
                table.addCell(personCell(person, background, height, l));
                for (int i = 0; i < days.size(); i++) {
                    table.addCell(dayCell(person.cells().get(i), days.get(i), background, height, l));
                }
                PdfPCell total = drawn(background, height, (rect, canvases) -> fitText(
                        canvases[PdfPTable.TEXTCANVAS], hm(person.minutes()), bold, sumSize, INK, rect, rect.getWidth() - 4));
                table.addCell(total);
            }
        }
        return table;
    }

    private PdfPCell personCell(Person person, Color background, float height, Layout l) {
        float k = l.k();
        PdfPCell cell = drawn(background, height, (rect, canvases) -> {
            PdfContentByte cb = canvases[PdfPTable.TEXTCANVAS];
            float x = rect.getLeft() + 5;
            float avail = rect.getWidth() - 9;
            if (person.civil()) {
                float size = shrink(regular, "↳ zlecenie", 7.5f * k, avail);
                text(cb, "↳ zlecenie", regular, size, SOFT, x + 6, middle(rect, size), Element.ALIGN_LEFT);
                return;
            }
            float size = shrink(bold, person.name(), name(k), avail);
            if (person.note() == null || person.note().isBlank()) {
                text(cb, person.name(), bold, size, INK, x, middle(rect, size), Element.ALIGN_LEFT);
                return;
            }
            float noteSize = shrink(regular, person.note(), 6.5f * k, avail);
            float block = cap(size) + 3 * k + cap(noteSize);
            float top = rect.getTop() - (rect.getHeight() - block) / 2;
            text(cb, person.name(), bold, size, INK, x, top - cap(size), Element.ALIGN_LEFT);
            text(cb, person.note(), regular, noteSize, SOFT, x, top - block, Element.ALIGN_LEFT);
        });
        return cell;
    }

    private PdfPCell dayCell(Cell value, Day day, Color background, float height, Layout l) {
        Color fill = day.closed() ? CLOSED : day.red() ? SUNDAY : background;
        PdfPCell cell = drawn(fill, height, (rect, canvases) -> drawCell(value, rect, canvases, l));
        weekRule(cell, day);
        return cell;
    }

    /** Jedna komórka dnia; ta sama w tabeli i w legendzie. */
    private void drawCell(Cell value, Rectangle rect, PdfContentByte[] canvases, Layout l) {
        float k = l.k();
        PdfContentByte cb = canvases[PdfPTable.TEXTCANVAS];
        float center = (rect.getLeft() + rect.getRight()) / 2;
        float avail = rect.getWidth() - CELL_PADDING;
        switch (value.kind()) {
            case WORK -> {
                float gap = gap(k);
                float block = cap(l.timeSize()) + gap + cap(l.endSize());
                if (value.hasNote()) {
                    block += noteGap(k) + badgeHeight(k);
                }
                float top = rect.getTop() - (rect.getHeight() - block) / 2;
                float start = top - cap(l.timeSize());
                time(cb, value.top(), bold, l.timeSize(), INK, center, start, avail);
                // Krótka kreska między początkiem a końcem: dwie liczby nigdy nie czytają się jako jedna.
                PdfContentByte lines = canvases[PdfPTable.LINECANVAS];
                lines.saveState();
                lines.setColorStroke(RULE);
                lines.setLineWidth(0.6f);
                float half = Math.min(rect.getWidth() * 0.28f, 6);
                lines.moveTo(center - half, start - gap / 2);
                lines.lineTo(center + half, start - gap / 2);
                lines.stroke();
                lines.restoreState();
                float end = start - gap - cap(l.endSize());
                time(cb, value.bottom(), regular, l.endSize(), INK, center, end, avail);
                if (value.hasNote()) {
                    badge(canvases, value.note(), center, end - noteGap(k), avail, k);
                }
            }
            case OFF -> {
                // Dzień wolny mniejszy i szary: dni pracy mają być widać pierwsze.
                float size = shrink(regular, value.top(), l.timeSize() * 0.95f, avail);
                text(cb, value.top(), regular, size, SOFT, center, middle(rect, size), Element.ALIGN_CENTER);
            }
            case ABSENCE -> {
                // Ramka i tło: urlop widać także na wydruku czarno-białym.
                PdfContentByte back = canvases[PdfPTable.BACKGROUNDCANVAS];
                float inset = 1.6f;
                back.saveState();
                back.setColorFill(ABSENCE);
                back.setColorStroke(INK);
                back.setLineWidth(0.8f);
                back.rectangle(rect.getLeft() + inset, rect.getBottom() + inset, rect.getWidth() - 2 * inset,
                        rect.getHeight() - 2 * inset);
                back.fillStroke();
                back.restoreState();
                float size = shrink(bold, value.top(), l.timeSize() * 1.15f, avail - 2);
                text(cb, value.top(), bold, size, INK, center, middle(rect, size), Element.ALIGN_CENTER);
            }
            case CLOSED, EMPTY -> {
            }
        }
    }

    /** Numer innego sklepu albo skrót stanowiska: mały szary znaczek na dole komórki. */
    private void badge(PdfContentByte[] canvases, String note, float center, float top, float avail, float k) {
        float size = shrink(bold, note, 6 * k, avail - 3);
        float width = bold.getWidthPoint(note, size) + 3;
        float height = badgeHeight(k);
        PdfContentByte back = canvases[PdfPTable.BACKGROUNDCANVAS];
        back.saveState();
        back.setColorFill(BADGE);
        back.roundRectangle(center - width / 2, top - height, width, height, 1.5f);
        back.fill();
        back.restoreState();
        text(canvases[PdfPTable.TEXTCANVAS], note, bold, size, INK, center, top - height + (height - cap(size)) / 2,
                Element.ALIGN_CENTER);
    }

    // --- legenda

    private PdfPTable legend(Sheet sheet, Layout l) throws DocumentException {
        float k = l.k();
        // Legenda nie maleje tak jak tabela: ma się dać przeczytać.
        float size = Math.clamp(8 * k, 7, 8.5f);
        int columns = Math.clamp(sheet.legend().size(), 4, 6);
        float sample = Math.max(l.dayWidth(), 18);
        float[] widths = new float[columns * 2];
        for (int i = 0; i < columns; i++) {
            widths[i * 2] = sample;
            widths[i * 2 + 1] = (l.width() - columns * sample) / columns;
        }
        PdfPTable table = new PdfPTable(widths);
        table.setTotalWidth(l.width());
        table.setLockedWidth(true);
        table.setSpacingBefore(7 * Math.min(k, 1));
        table.setKeepTogether(true);

        PdfPCell title = new PdfPCell(new Phrase("JAK CZYTAĆ GRAFIK", font(bold, size, MUTED)));
        title.setColspan(widths.length);
        title.setBorder(Rectangle.BOTTOM);
        title.setBorderColor(RULE);
        title.setPaddingLeft(0);
        title.setPaddingBottom(3);
        table.addCell(title);

        List<LegendItem> items = sheet.legend();
        int cells = (items.size() + columns - 1) / columns * columns;
        for (int i = 0; i < cells; i++) {
            if (i < items.size()) {
                LegendItem item = items.get(i);
                float height = item.sample().hasNote() ? l.noteRowHeight() : l.rowHeight();
                PdfPCell box = drawn(Color.WHITE, height, (rect, canvases) -> drawCell(item.sample(), rect, canvases, l));
                box.setBorder(Rectangle.BOX);
                box.setBorderColor(RULE);
                table.addCell(spaced(box));
                PdfPCell text = new PdfPCell(new Phrase(item.text(), font(regular, size, INK)));
                text.setBorder(Rectangle.NO_BORDER);
                text.setVerticalAlignment(Element.ALIGN_MIDDLE);
                text.setPaddingLeft(5);
                text.setPaddingRight(8);
                text.setLeading(0, 1.15f);
                table.addCell(text);
            } else {
                table.addCell(blank());
                table.addCell(blank());
            }
        }

        Phrase notes = new Phrase();
        notes.setLeading(size * 1.35f);
        notes.add(new Chunk("Godz. ", font(bold, size, INK)));
        notes.add(new Chunk("– suma godzin pracy w miesiącu.   ", font(regular, size, INK)));
        notes.add(new Chunk("Czerwony dzień ", font(bold, size, SUNDAY_INK)));
        notes.add(new Chunk("– niedziela albo święto.   ", font(regular, size, INK)));
        notes.add(new Chunk("Szary dzień ", font(bold, size, INK)));
        notes.add(new Chunk("– sklep zamknięty.   ", font(regular, size, INK)));
        notes.add(new Chunk("Pusta kratka ", font(bold, size, INK)));
        notes.add(new Chunk("– dzień bez pracy.", font(regular, size, INK)));
        List<Day> holidays = sheet.days().stream().filter(day -> day.holiday() != null).toList();
        if (!holidays.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (Day day : holidays) {
                text.append(text.isEmpty() ? "" : ";  ").append(DAY.format(day.date())).append(" ")
                        .append(day.holiday()).append(day.closed() ? " (sklep zamknięty)" : " (sklep czynny)");
            }
            notes.add(new Chunk("\nŚwięta: ", font(bold, size, INK)));
            notes.add(new Chunk(text.toString(), font(regular, size, INK)));
        }
        PdfPCell footer = new PdfPCell(notes);
        footer.setColspan(widths.length);
        footer.setBorder(Rectangle.NO_BORDER);
        footer.setPaddingLeft(0);
        footer.setPaddingTop(4);
        table.addCell(footer);
        return table;
    }

    private static PdfPCell spaced(PdfPCell cell) {
        cell.setPaddingTop(2);
        return cell;
    }

    private static PdfPCell blank() {
        PdfPCell cell = new PdfPCell(new Phrase(" "));
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    // --- rysowanie

    @FunctionalInterface
    private interface Painter {
        void paint(Rectangle rect, PdfContentByte[] canvases);
    }

    /** Komórka o stałej wysokości, której treść rysuje {@code painter}. */
    private static PdfPCell drawn(Color background, float height, Painter painter) {
        PdfPCell cell = new PdfPCell(new Phrase(""));
        cell.setFixedHeight(height);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setBackgroundColor(background);
        cell.setCellEvent(new PdfPCellEvent() {
            @Override
            public void cellLayout(PdfPCell layoutCell, Rectangle position, PdfContentByte[] canvases) {
                painter.paint(position, canvases);
            }
        });
        return cell;
    }

    private static PdfPCell textCell(Phrase phrase, int align, Color background) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(RULE);
        cell.setBorderWidth(0.5f);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(background);
        cell.setPaddingTop(1);
        cell.setPaddingBottom(2.5f);
        cell.setPaddingLeft(1);
        cell.setPaddingRight(1);
        return cell;
    }

    /** Grubsza ciemna kreska po niedzieli: tygodnie widać od razu. */
    private static void weekRule(PdfPCell cell, Day day) {
        if (day.date().getDayOfWeek() == DayOfWeek.MONDAY && day.date().getDayOfMonth() > 1) {
            cell.setUseVariableBorders(true);
            cell.setBorderWidthLeft(1.6f);
            cell.setBorderColorLeft(WEEK_RULE);
        }
    }

    /** Godzina: pełna liczbą, minuty małymi cyframi u góry (13³⁰), wyśrodkowana. */
    private void time(PdfContentByte cb, String value, BaseFont base, float size, Color color, float center,
            float baseline, float avail) {
        float scaled = Math.min(size, size * avail / Math.max(timeWidth(base, value, size), 0.01f));
        int colon = value.indexOf(':');
        if (colon < 0) {
            text(cb, value, base, scaled, color, center, baseline, Element.ALIGN_CENTER);
            return;
        }
        String hour = value.substring(0, colon);
        String minutes = value.substring(colon + 1);
        float minuteSize = scaled * 0.62f;
        float hourWidth = base.getWidthPoint(hour, scaled);
        float left = center - timeWidth(base, value, scaled) / 2;
        text(cb, hour, base, scaled, color, left, baseline, Element.ALIGN_LEFT);
        text(cb, minutes, base, minuteSize, color, left + hourWidth + scaled * 0.05f, baseline + cap(scaled) - cap(minuteSize),
                Element.ALIGN_LEFT);
    }

    private static float timeWidth(BaseFont base, String value, float size) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            return base.getWidthPoint(value, size);
        }
        return base.getWidthPoint(value.substring(0, colon), size)
                + base.getWidthPoint(value.substring(colon + 1), size * 0.62f) + size * 0.05f;
    }

    private void fitText(PdfContentByte cb, String value, BaseFont base, float size, Color color, Rectangle rect,
            float avail) {
        if (value.isEmpty()) {
            return;
        }
        float scaled = shrink(base, value, size, avail);
        text(cb, value, base, scaled, color, (rect.getLeft() + rect.getRight()) / 2, middle(rect, scaled),
                Element.ALIGN_CENTER);
    }

    private static float shrink(BaseFont base, String value, float size, float avail) {
        float width = base.getWidthPoint(value, size);
        return width <= avail ? size : size * avail / width;
    }

    private static void text(PdfContentByte cb, String value, BaseFont base, float size, Color color, float x,
            float baseline, int align) {
        cb.saveState();
        cb.beginText();
        cb.setFontAndSize(base, size);
        cb.setColorFill(color);
        cb.showTextAligned(align, value, x, baseline, 0);
        cb.endText();
        cb.restoreState();
    }

    /** Linia bazowa, przy której wielkie litery i cyfry stoją na środku komórki. */
    private static float middle(Rectangle rect, float size) {
        return rect.getBottom() + (rect.getHeight() - cap(size)) / 2;
    }

    /** Wysokość cyfr i wielkich liter DejaVu Sans. */
    private static float cap(float size) {
        return size * 0.73f;
    }

    /** Nazwisko rośnie wolniej niż wiersz, żeby długie nazwiska nie poszerzały kolumny ponad miarę. */
    private static float name(float k) {
        return 9.5f * Math.min(k, 1.1f);
    }

    private static float badgeHeight(float k) {
        return 7 * k;
    }

    /** Odstęp między początkiem a końcem, w którym stoi kreska. */
    private static float gap(float k) {
        return 3.6f * k;
    }

    private static float noteGap(float k) {
        return 2 * k;
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
