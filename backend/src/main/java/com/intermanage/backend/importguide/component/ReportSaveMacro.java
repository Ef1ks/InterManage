package com.intermanage.backend.importguide.component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.dto.ImportCoverage;
import com.intermanage.backend.importguide.entity.ImportReportKind;

/**
 * Makro dla odtwarzacza (Alt+E): raporty z Mercalys otwarte w Excelu (lista promocji w PDF) i zapisane pod ścieżkami
 * z ustawień importu, skąd bierze je „Odśwież dane” albo agent sklepu. Jedno źródło dla przeglądarki („Zaktualizuj
 * wszystkie pliki”) i agenta (aktualizacja przy starcie komputera); dawniej składał je tylko front.
 *
 * Sprzedaż i dokumenty drukują też zaległość: dziury od {@link #HISTORY_START} (albo od dnia otwarcia sklepu)
 * w okresach wgranych raportów, każda podzielona na części najwyżej do limitu Mercalys, od najstarszej.
 */
public final class ReportSaveMacro {

    /** Okno Mercalys dla poleceń strony i okno programu (WINOPEN, WINMIN). */
    static final String WINDOW = "Mercalys";
    public static final String PROGRAM_WINDOW = "Mercalys ahk_exe Mercalys.exe";
    /** Pierwsze polecenie makra; klient dokłada do niego hasło Mercalys ({@code |hasło}), gdy je zna. */
    public static final String OPEN_LINE = "WINOPEN|" + PROGRAM_WINDOW;

    static final LocalDate HISTORY_START = LocalDate.of(2024, 1, 1);
    public static final String OPENED_ON_KEY = "import:openedOn";

    private static final int PAGE_TIMEOUT_MS = 30_000;
    /** Po kliku w menu na otwartej już karcie (ponowienie): bez przerwy Excel kliknąłby się na starej stronie. */
    private static final int MENU_SLEEP_MS = 1_500;
    private static final int EXCEL_WAIT_MS = 600_000;
    private static final int LONG_EXCEL_WAIT_MS = 1_800_000;
    private static final int DOCUMENTS_EXCEL_WAIT_MS = 3_600_000;
    private static final int EMPTY_RESULT_WAIT_MS = 20_000;
    private static final int STATISTIC_TIMEOUT_MS = 600_000;
    private static final int TABLE_TIMEOUT_MS = 1_800_000;
    private static final int DOCUMENTS_OVERLAP_DAYS = 2;

    /**
     * Ile razy odtwarzacz (od 0.69) próbuje zapisać jeden plik, zanim przejdzie do następnego. Linie {@code #PART},
     * {@code #ALT} (kroki ponowienia) i {@code #ENDPARTS} są komentarzami dla starszego odtwarzacza.
     */
    private static final int PART_ATTEMPTS = 3;

    /** Raporty z makrem, w kolejności odświeżania. */
    public static final List<ImportReportKind> MACRO_REPORTS = List.of(ImportReportKind.PRODUCTS,
            ImportReportKind.PRODUCT_CODES, ImportReportKind.PROMOTIONS, ImportReportKind.CADENCIER,
            ImportReportKind.SUPPLIERS, ImportReportKind.SALES, ImportReportKind.DOCUMENTS);

    /** Limit okresu jednego wydruku w Mercalys (miesiące). */
    static final Map<ImportReportKind, Integer> PERIOD_MONTHS = Map.of(ImportReportKind.SALES, 6,
            ImportReportKind.DOCUMENTS, 3);

    private static final Map<ImportReportKind, String> TITLES = Map.of(ImportReportKind.PRODUCTS, "Baza danych",
            ImportReportKind.PRODUCT_CODES, "Multikody", ImportReportKind.PROMOTIONS, "Lista promocji",
            ImportReportKind.SALES, "Raport sprzedaży", ImportReportKind.DOCUMENTS, "Raport dokumentów",
            ImportReportKind.CADENCIER, "Obecne w cadencier", ImportReportKind.SUPPLIERS, "Lista dostawców");

    private static final DateTimeFormatter TYPED = DateTimeFormatter.ofPattern("ddMMyyyy");

    private ReportSaveMacro() {
    }

    public static String title(ImportReportKind kind) {
        return TITLES.getOrDefault(kind, kind.name());
    }

    /** Okres wydruku. */
    public record Period(LocalDate from, LocalDate to) {
    }

    /** Ustawienia raportu ze ścieżek sklepu ({@code import:<RAPORT>:path|print|list|part|mask}). */
    public record Settings(String path, String printNumber, String listNumber, String partLength, String mask) {

        public static Settings of(Map<String, String> locations, ImportReportKind kind) {
            String prefix = "import:" + kind.name() + ":";
            return new Settings(locations.get(prefix + "path"), locations.get(prefix + "print"),
                    locations.get(prefix + "list"), locations.get(prefix + "part"), locations.get(prefix + "mask"));
        }
    }

    /** Plik, który makro zapisze (część okresu albo cały raport). */
    public record SavedFile(ImportReportKind kind, String path, String fileName) {
    }

    /** Raport pominięty, bo brakuje mu ustawień, z powodem do pokazania. */
    public record Skipped(ImportReportKind kind, String reason) {
    }

    public record Result(String macro, List<ImportReportKind> reports, List<Skipped> skipped,
            List<SavedFile> files, String playerVersion) {
    }

    /** Makro na podane raporty (bez ustawień: pominięte z powodem). */
    public static Result build(List<ImportReportKind> kinds, Map<String, String> locations, ImportCoverage coverage,
            LocalDate today) {
        LocalDate openedOn = parseDate(locations.get(OPENED_ON_KEY));
        List<ImportReportKind> ready = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (ImportReportKind kind : MACRO_REPORTS) {
            if (!kinds.contains(kind)) {
                continue;
            }
            String reason = notReady(kind, Settings.of(locations, kind));
            if (reason == null) {
                ready.add(kind);
            } else {
                skipped.add(new Skipped(kind, reason));
            }
        }
        String version = playerVersion(ready);
        List<String> lines = new ArrayList<>();
        List<SavedFile> files = new ArrayList<>();
        lines.add("# Intermanage: zapis raportów z Mercalys do folderów importu (odtwarzacz od wersji " + version + ")");
        lines.add(OPEN_LINE);
        for (int index = 0; index < ready.size(); index++) {
            ImportReportKind kind = ready.get(index);
            Settings settings = Settings.of(locations, kind);
            String title = title(kind);
            String step = ready.size() > 1 ? " (" + (index + 1) + " z " + ready.size() + ")" : "";
            List<Period> parts = periodParts(kind, coverage, openedOn, settings.partLength(), today);
            for (int part = 0; part < parts.size(); part++) {
                Period period = parts.get(part);
                String emptyLabel = "pusty-" + (index + 1) + "-" + (part + 1);
                boolean continuing = part > 0;
                List<String> steps = new ArrayList<>(openSteps(kind, settings, period, emptyLabel, continuing, today));
                String partLabel = parts.size() > 1 && period != null
                        ? ", część " + (part + 1) + " z " + parts.size() + ": " + period.from() + " - " + period.to()
                        : "";
                String path = partPath(settings.path().strip(), part + 1, parts.size());
                files.add(new SavedFile(kind, path, path.substring(path.lastIndexOf('\\') + 1)));
                int wait = switch (kind) {
                    case CADENCIER, SUPPLIERS -> EXCEL_WAIT_MS;
                    case DOCUMENTS -> DOCUMENTS_EXCEL_WAIT_MS;
                    default -> LONG_EXCEL_WAIT_MS;
                };
                List<String> save = new ArrayList<>();
                save.add((kind == ImportReportKind.PROMOTIONS ? "PDFSAVE|" : "EXCELSAVE|") + path + "|" + wait);
                if (kind == ImportReportKind.SALES) {
                    save.add("LABEL|" + emptyLabel);
                }
                lines.add("#PART|" + PART_ATTEMPTS + "|" + title + step + partLabel);
                lines.addAll(steps);
                lines.addAll(save);
                if (kind == ImportReportKind.DOCUMENTS && continuing) {
                    // Po błędzie formularz nie stoi już na poprzedniej części: ponowienie otwiera go z menu od nowa.
                    openSteps(kind, settings, period, emptyLabel, false, today).forEach(line -> lines.add("#ALT|" + line));
                    save.forEach(line -> lines.add("#ALT|" + line));
                }
            }
        }
        lines.add("#ENDPARTS");
        lines.add("WINMIN|" + PROGRAM_WINDOW);
        return new Result(String.join("\r\n", lines) + "\r\n", List.copyOf(ready), List.copyOf(skipped),
                List.copyOf(files), version);
    }

    /** Najstarszy odtwarzacz dla tych raportów: pusty wynik sprzedaży od 0.55, zapis PDF od 0.49. */
    static String playerVersion(List<ImportReportKind> kinds) {
        if (kinds.contains(ImportReportKind.SALES)) {
            return "0.55";
        }
        return kinds.contains(ImportReportKind.PROMOTIONS) ? "0.49" : "0.46";
    }

    /** Czego brakuje raportowi do makra (null = gotowy). Te same zasady co ustawienia importu we froncie. */
    static String notReady(ImportReportKind kind, Settings settings) {
        String path = settings.path() == null ? "" : settings.path().strip();
        if (path.isEmpty()) {
            return "brak ścieżki zapisu z Mercalys w ustawieniach importu";
        }
        String pathError = pathError(kind, path, settings.mask() == null ? "" : settings.mask());
        if (pathError != null) {
            return pathError;
        }
        if ((kind == ImportReportKind.PRODUCTS || kind == ImportReportKind.CADENCIER)
                && (settings.printNumber() == null || !settings.printNumber().strip().matches("\\d{1,2}"))) {
            return "brak numeru wydruku z Mercalys w ustawieniach importu";
        }
        if (kind == ImportReportKind.CADENCIER
                && (settings.listNumber() == null || !settings.listNumber().strip().matches("\\d{1,4}"))) {
            return "brak numeru listy z Mercalys w ustawieniach importu";
        }
        return null;
    }

    private static String pathError(ImportReportKind kind, String path, String mask) {
        if (!Pattern.compile("^([a-z]:\\\\|\\\\\\\\[^\\\\]+\\\\[^\\\\]+)", Pattern.CASE_INSENSITIVE).matcher(path)
                .find()) {
            return "ścieżka zapisu nie jest pełną ścieżką";
        }
        if (path.contains("|")) {
            return "ścieżka zapisu ma znak |";
        }
        String name = path.substring(path.lastIndexOf('\\') + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        boolean pdf = kind == ImportReportKind.PROMOTIONS;
        if (pdf ? !extension.equals(".pdf") : !(extension.equals(".xlsx") || extension.equals(".xlsm"))) {
            return "zła końcówka pliku w ścieżce zapisu";
        }
        if (!mask.isBlank() && !maskPattern(mask).matcher(name.substring(0, dot)).matches()) {
            return "nazwa pliku nie pasuje do maski";
        }
        return null;
    }

    /** Maska jak w Eksploratorze ({@code *}, {@code ?}, bez wielkości liter); bez gwiazdki pasuje też z dopiskiem. */
    static Pattern maskPattern(String mask) {
        String value = mask.strip();
        StringBuilder source = new StringBuilder("^");
        for (char c : value.toCharArray()) {
            if (c == '*') {
                source.append(".*");
            } else if (c == '?') {
                source.append('.');
            } else if (".+^${}()|[]\\".indexOf(c) >= 0) {
                source.append('\\').append(c);
            } else {
                source.append(c);
            }
        }
        if (!value.contains("*")) {
            source.append(".*");
        }
        return Pattern.compile(source.append('$').toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // --- Okresy --------------------------------------------------------------------------------------------------

    /** Długość części zaległego okresu (tygodnie albo miesiące). */
    record PartLength(boolean weeks, int count) {
    }

    /** Długość z ustawień ({@code 1w}, {@code 2w}, {@code 1m}…); bez niej albo ponad limit = limit Mercalys. */
    static PartLength partLength(ImportReportKind kind, String value) {
        int limit = PERIOD_MONTHS.getOrDefault(kind, 1);
        String text = value == null ? "" : value.strip();
        if (text.matches("[12]w")) {
            return new PartLength(true, text.charAt(0) - '0');
        }
        if (text.matches("[1236]m") && text.charAt(0) - '0' <= limit) {
            return new PartLength(false, text.charAt(0) - '0');
        }
        return new PartLength(false, limit);
    }

    /**
     * Części okresu od najstarszej: dziury od początku historii w okresach wgranych raportów, potem bieżący okres
     * (sprzedaż: wczoraj i dziś, dokumenty: od przedwczoraj do jutra). Raport bez okresu to jedna część bez dat.
     */
    static List<Period> periodParts(ImportReportKind kind, ImportCoverage coverage, LocalDate openedOn,
            String partLength, LocalDate today) {
        List<Period> result = new ArrayList<>();
        if (!PERIOD_MONTHS.containsKey(kind)) {
            result.add(null);
            return result;
        }
        PartLength length = partLength(kind, partLength);
        boolean sales = kind == ImportReportKind.SALES;
        Period current = sales ? new Period(today.minusDays(1), today)
                : new Period(today.minusDays(2), today.plusDays(1));
        List<ImportCoverage.DateRange> covered = coverage == null ? List.of()
                : sales ? coverage.sales() : coverage.documents();
        LocalDate start = openedOn != null && openedOn.isAfter(HISTORY_START) ? openedOn : HISTORY_START;
        int overlap = kind == ImportReportKind.DOCUMENTS ? DOCUMENTS_OVERLAP_DAYS : 0;
        List<Period> gaps = missingPeriods(covered, start, current.from().minusDays(1));
        List<Period> periods = new ArrayList<>();
        for (int i = 0; i < gaps.size(); i++) {
            Period gap = gaps.get(i);
            LocalDate earliest = i > 0 ? gaps.get(i - 1).to().plusDays(1) : start;
            LocalDate from = gap.from().minusDays(overlap);
            periods.add(new Period(from.isBefore(earliest) ? earliest : from, gap.to()));
        }
        periods.add(new Period(current.from().isBefore(start) ? start : current.from(), current.to()));
        // Okresy, które się stykają albo nachodzą (zaległy dzień tuż przed bieżącym okresem), to jeden wydruk; dzieli
        // go dopiero limit Mercalys. Inaczej 21.11 i 22-23.11 szły dwoma plikami.
        List<Period> merged = new ArrayList<>();
        for (Period period : periods) {
            if (period.from().isAfter(period.to())) {
                continue;
            }
            Period last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && !period.from().isAfter(last.to().plusDays(1))) {
                merged.set(merged.size() - 1,
                        new Period(last.from(), period.to().isAfter(last.to()) ? period.to() : last.to()));
            } else {
                merged.add(period);
            }
        }
        for (Period period : merged) {
            if (!period.from().isAfter(period.to())) {
                result.addAll(splitPeriod(period, length, kind == ImportReportKind.DOCUMENTS));
            }
        }
        return result;
    }

    /** Dni od {@code from} do {@code to}, których nie obejmuje żaden okres z {@code covered}, od najstarszego. */
    static List<Period> missingPeriods(List<ImportCoverage.DateRange> covered, LocalDate from, LocalDate to) {
        List<Period> gaps = new ArrayList<>();
        LocalDate next = from;
        List<ImportCoverage.DateRange> sorted = covered.stream()
                .sorted(Comparator.comparing(ImportCoverage.DateRange::from)).toList();
        for (ImportCoverage.DateRange range : sorted) {
            if (next.isAfter(to)) {
                break;
            }
            if (range.to().isBefore(next)) {
                continue;
            }
            if (range.from().isAfter(next)) {
                LocalDate end = range.from().minusDays(1);
                gaps.add(new Period(next, end.isBefore(to) ? end : to));
            }
            next = range.to().plusDays(1);
        }
        if (!next.isAfter(to)) {
            gaps.add(new Period(next, to));
        }
        return gaps;
    }

    /** Okres na części najwyżej po {@code length}, od najstarszej. */
    static List<Period> splitPeriod(Period period, PartLength length) {
        return splitPeriod(period, length, false);
    }

    /**
     * Okres na części najwyżej po {@code length}, od najstarszej. Z {@code interlocked} kolejna część zaczyna się
     * w dniu końca poprzedniej (data max staje się datą min), bo formularz dokumentów zostaje z datami poprzedniej
     * części i Mercalys nie przyjmie daty min późniejszej od starej daty max; dzień wspólny import scala.
     */
    static List<Period> splitPeriod(Period period, PartLength length, boolean interlocked) {
        List<Period> parts = new ArrayList<>();
        LocalDate from = period.from();
        while (true) {
            LocalDate end = length.weeks() ? from.plusDays(length.count() * 7L - 1)
                    : from.plusMonths(length.count()).minusDays(1);
            LocalDate to = end.isBefore(period.to()) ? end : period.to();
            parts.add(new Period(from, to));
            if (!to.isBefore(period.to())) {
                return parts;
            }
            from = interlocked ? to : to.plusDays(1);
        }
    }

    /** Ścieżka części („sprzedaz 1 z 3.xlsx”); ostatnia, najnowsza ma ścieżkę z ustawień. */
    static String partPath(String path, int part, int parts) {
        if (part == parts) {
            return path;
        }
        int dot = path.lastIndexOf('.');
        return path.substring(0, dot) + " " + part + " z " + parts + path.substring(dot);
    }

    // --- Kroki w Mercalys ----------------------------------------------------------------------------------------

    private static String typed(LocalDate date) {
        return date.format(TYPED);
    }

    /** Wartość pola daty po wpisaniu (DD/MM/RRRR, ukośniki i wiek opcjonalne). */
    private static String datePattern(LocalDate date) {
        String text = typed(date);
        return "^" + text.substring(0, 2) + "/?" + text.substring(2, 4) + "/?(" + text.substring(4, 6) + ")?"
                + text.substring(6) + "$";
    }

    private static List<String> openSteps(ImportReportKind kind, Settings settings, Period period, String emptyLabel,
            boolean continuing, LocalDate today) {
        String w = WINDOW;
        return switch (kind) {
            case PRODUCTS, CADENCIER -> {
                String number = settings.printNumber().strip();
                List<String> steps = new ArrayList<>(List.of(
                        "WEBENSURE|" + w + "|mnTv_ARTICLE_EditionArticle|mnTp_ARTICLE",
                        "WEBOPEN|" + w + "|mnTv_ARTICLE_EditionArticle|mnTv_ARTICLE_ar-e027_EditionParametrable"
                                + "|lstZ1_NumeroEdition_TxtBox",
                        "WEBWAIT|" + w + "|lstZ1_NumeroEdition_TxtBox||" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|lstZ1_NumeroEdition_TxtBox|^a" + number + "{Tab}",
                        "WEBVALUE|" + w + "|lstZ1_NumeroEdition_TxtBox|^0*" + Integer.parseInt(number) + "$|"
                                + PAGE_TIMEOUT_MS,
                        "WEBVALUE|" + w + "|txtZ4_Titre1_TxtBox|\\S|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + w + "|btnB4_Excel_LnkBouton",
                        "WEBPAGE|" + w + "|ar-e054|WG ITM8|" + PAGE_TIMEOUT_MS));
                if (kind == ImportReportKind.PRODUCTS) {
                    steps.add("WEBCLICK|" + w + "|WG ITM8");
                    steps.add("WEBWAIT|" + w + "|txtZ1_ItmMin_TxtBox||" + PAGE_TIMEOUT_MS);
                    steps.add("WEBCLICK|" + w + "|btnB2_Valider_LnkBouton");
                } else {
                    // Numer listy z Enterem przewija listę do jej strony, ale wiersza nie zaznacza: dwuklik w wiersz.
                    String list = String.valueOf(Integer.parseInt(settings.listNumber().strip()));
                    steps.add("WEBCLICK|" + w + "|lkbAutres_LinkAviso");
                    steps.add("WEBWAIT|" + w + "|txtZ1_NumListe_TxtBox||" + PAGE_TIMEOUT_MS);
                    steps.add("WEBKEY|" + w + "|txtZ1_NumListe_TxtBox|^a" + list + "{Enter}");
                    steps.add("WEBDBLROW|" + w + "|*|" + list + "|" + PAGE_TIMEOUT_MS);
                }
                yield steps;
            }
            case PRODUCT_CODES -> List.of(
                    "WEBENSURE|" + w + "|mnTv_ARTICLE_ar-e002_FicheEan|mnTp_ARTICLE",
                    "WEBCLICK|" + w + "|mnTv_ARTICLE_ar-e002_FicheEan",
                    "WEBPAGE|" + w + "|ar-e054|WG ITM8|" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + w + "|WG ITM8",
                    "WEBWAIT|" + w + "|txtZ1_ItmMin_TxtBox||" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + w + "|btnB2_Valider_LnkBouton",
                    "WEBPAGE|" + w + "|ar-e056|btnB5_Excel_LnkBouton|" + TABLE_TIMEOUT_MS,
                    "WEBCLICK|" + w + "|btnB5_Excel_LnkBouton");
            // Obsługa → Dostawcy (jak makro nowego dostawcy) → Excel: zakres kodów zostaje domyślny, czyli cała lista.
            case SUPPLIERS -> List.of(
                    "WEBENSURE|" + w + "|mnTv_MAINTENANCE_ac-e001_Fournisseurs|mnTp_MAINTENANCE",
                    "WEBCLICK|" + w + "|mnTv_MAINTENANCE_ac-e001_Fournisseurs",
                    "SLEEP|" + MENU_SLEEP_MS,
                    "WEBPAGE|" + w + "|ac-e001|btnB10_Excel_LnkBouton|" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + w + "|btnB10_Excel_LnkBouton",
                    "WEBWAIT|" + w + "|lstZ2_FrsMax_TxtBox||" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + w + "|btnB2_Valider_LnkBouton");
            case PROMOTIONS -> {
                // Data końca do = dziś + 2 lata (29 lutego przechodzi na 28).
                int year = today.getYear() + 2;
                LocalDate end = YearMonth.of(year, today.getMonthValue())
                        .atDay(Math.min(today.getDayOfMonth(), YearMonth.of(year, today.getMonthValue()).lengthOfMonth()));
                String day = String.format("%02d", end.getDayOfMonth());
                String month = String.format("%02d", end.getMonthValue());
                String y = String.valueOf(year);
                yield List.of(
                        "WEBENSURE|" + w + "|mnTv_TARIF_ar-e079_ListeDesPromosEnCours|mnTp_TARIF",
                        "WEBCLICK|" + w + "|mnTv_TARIF_ar-e079_ListeDesPromosEnCours",
                        "WEBWAIT|" + w + "|txdZ2_DateFinMax_TxtBox||" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|txdZ2_DateFinMax_TxtBox|^a" + day + month + y + "{Tab}",
                        "WEBVALUE|" + w + "|txdZ2_DateFinMax_TxtBox|^" + day + "/?" + month + "/?(" + y.substring(0, 2)
                                + ")?" + y.substring(2) + "$|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + w + "|btnB2_Valider_LnkBouton");
            }
            case SALES -> {
                List<String> steps = new ArrayList<>(List.of(
                        "WEBENSURE|" + w + "|mnTv_PILOTAGE_StatistiquesStandard|mnTp_PILOTAGE",
                        "WEBOPEN|" + w + "|mnTv_PILOTAGE_StatistiquesStandard"
                                + "|mnTv_PILOTAGE_selectionstatistique_EntreesSortiesStandard"
                                + "|ListeStatistiques__ctl0_LinkButtonListe_LinkAviso",
                        "WEBCLICK|" + w + "|ListeStatistiques__ctl0_LinkButtonListe_LinkAviso",
                        "WEBWAIT|" + w + "|_ctl11_ListeDeroulanteMP1_TxtBox||" + PAGE_TIMEOUT_MS));
                for (String[] choice : new String[][] {{"_ctl11_ListeDeroulanteMP1_TxtBox", "1"},
                        {"_ctl12_ListeDeroulanteMP1_TxtBox", "2"}, {"_ctl13_ListeDeroulanteMP1_TxtBox", "3"}}) {
                    steps.add("WEBKEY|" + w + "|" + choice[0] + "|^a" + choice[1] + "{Tab}");
                    steps.add("WEBVALUE|" + w + "|" + choice[0] + "|^" + choice[1] + "$|" + PAGE_TIMEOUT_MS);
                }
                String from = "_ctl16_TextEditDateDattyp1Min_TxtBox";
                String to = "_ctl16_TextEditDateDattyp1Max_TxtBox";
                steps.addAll(List.of(
                        "WEBCLICK|" + w + "|ButtonAvisoPar__LnkBouton",
                        "WEBWAIT|" + w + "|" + from + "||" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|" + from + "|^a" + typed(period.from()) + "{Tab}",
                        "WEBVALUE|" + w + "|" + from + "|" + datePattern(period.from()) + "|" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|" + to + "|^a" + typed(period.to()) + "{Tab}",
                        "WEBVALUE|" + w + "|" + to + "|" + datePattern(period.to()) + "|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + w + "|ButtonAvisoValider__LnkBouton",
                        // Okres bez sprzedaży daje pusty wynik bez przycisku Excel: ta część nie ma pliku.
                        "WEBPAGE|" + w + "|AfficheResultat.aspx|ButtonAvisoRetour__LnkBouton|" + STATISTIC_TIMEOUT_MS,
                        "WEBIFSEEN|" + w + "|ButtonAvisoForexc__LnkBouton|" + emptyLabel + "|" + EMPTY_RESULT_WAIT_MS
                                + "|Sprzedaż " + period.from() + " - " + period.to() + ": pusty wynik, bez pliku",
                        "WEBCLICK|" + w + "|ButtonAvisoForexc__LnkBouton"));
                yield steps;
            }
            case DOCUMENTS -> {
                String from = "txdZ23_DateMin_TxtBox";
                String to = "txdZ25_DateMax_TxtBox";
                List<String> steps = new ArrayList<>();
                // Kolejna część: formularz zostaje na ekranie, ponowny klik w menu przeładowałby go ze starymi datami.
                if (!continuing) {
                    steps.add("WEBENSURE|" + w + "|mnTv_PILOTAGE_Etats|mnTp_PILOTAGE");
                    steps.add("WEBOPEN|" + w + "|mnTv_PILOTAGE_Etats"
                            + "|mnTv_VENTE_ve-e048_rapportMouvenementEnFonctionDocuments|" + from);
                }
                // Mercalys sprawdza daty po wyjściu z pola: kolejna część zaczyna się po końcu poprzedniej, więc data
                // min wpisana pierwsza byłaby późniejsza niż stara data max („data min nie może być większa od daty
                // max”). Wtedy najpierw data max (jest późniejsza od starej), potem min.
                String first = continuing ? to : from;
                String second = continuing ? from : to;
                LocalDate firstDate = continuing ? period.to() : period.from();
                LocalDate secondDate = continuing ? period.from() : period.to();
                steps.addAll(List.of(
                        "WEBWAIT|" + w + "|" + from + "||" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|" + first + "|^a" + typed(firstDate) + "{Tab}",
                        "WEBVALUE|" + w + "|" + first + "|" + datePattern(firstDate) + "|" + PAGE_TIMEOUT_MS,
                        "WEBKEY|" + w + "|" + second + "|^a" + typed(secondDate) + "{Tab}",
                        "WEBVALUE|" + w + "|" + second + "|" + datePattern(secondDate) + "|" + PAGE_TIMEOUT_MS,
                        "WEBVALUE|" + w + "|" + first + "|" + datePattern(firstDate) + "|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + w + "|btnB2_Valider_LnkBouton"));
                yield steps;
            }
            default -> throw new IllegalArgumentException("Raport bez makra: " + kind);
        };
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
