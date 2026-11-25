package com.intermanage.backend.dataentry.component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro zmian cen składane przez serwer (agent sklepu wykonuje tylko makra z serwera): te same polecenia co
 * {@code buildPriceControlMacro} w {@code mercalys-macro.ts}; zmiana ekranów Mercalys = zmiana w obu miejscach.
 *
 * <p>Dla każdego pliku Taryfa → Kontrola ceny → Pobranie danych → Historia, opracowanie wiersza z datą i godziną pliku,
 * zamknięcie PDF-a, potem Różne ceny: Zaznacz wszystko, Porównaj MSI. Pliku, którego nie ma w historii, makro nie rusza
 * (wpis w podsumowaniu). Wymaga odtwarzacza od 0.60.
 */
public final class PriceControlMacro {

    private static final String WINDOW = "Mercalys";
    private static final String DIALOG = "Les Mousquetaires";
    private static final String PDF_WINDOW = "ahk_class AcrobatSDIWindow";
    private static final int PAGE_TIMEOUT_MS = 30000;
    /** Opracowanie dużego pliku i wyrównanie cen potrafią trwać. */
    private static final int WORK_TIMEOUT_MS = 120000;
    private static final int ALERT_MS = 15000;

    private static final String TARIFF_TAB = "mnTp_TARIF";
    private static final String CONTROL_MENU = "mnTv_TARIF_ControlePrix";
    private static final String DOWNLOAD_MENU = "mnTv_TARIF_ar-e083_Dechargement";
    private static final String HISTORY = "btnHistorique_LnkBouton";
    private static final String HISTORY_BACK = "btnRetourHisto_LnkBouton";
    private static final String HISTORY_GRID = "dgHisto";
    private static final String PROCESS = "ImgButtonTraiter_ImageButton1";
    private static final String NEXT_PAGE = "pgrDetail_ibtSuivant";
    private static final int HISTORY_PAGES = 60;
    /** „Przetworzyć plik ponownie?” */
    private static final String PROCESS_TEXT = "ponownie";
    private static final String DIFFERENT_PRICES_MENU = "mnTv_TARIF_ar-e084_PrixDifferents";
    private static final String SELECT_ALL = "btnB2_SelectTous_LnkBouton";
    private static final String ALIGN_MSI = "btnB6_AlignerMSI_LnkBouton";
    private static final String SELECTED_PAGE =
            "i)Suma linii\\s*:\\s*([1-9]\\d*)(?!\\d)[\\s\\S]*?Suma wyboru\\s*:\\s*\\1(?!\\d)";
    /**
     * Po Porównaj MSI artykuły w promocji zostają w tabeli bez zaznaczenia, więc czekamy na „Suma wyboru: 0”, a nie na
     * pustą tabelę ani na wygląd wiersza (gwiazdka nie łapała każdej promocji).
     */
    private static final String ALIGNED_PAGE = "i)Suma wyboru\\s*:\\s*0(?!\\d)";
    private static final String ALERT_WINDOW = "ahk_class Internet Explorer_TridentDlgFrame";
    private static final String ALERT_TEXT = "promocji";

    private static final Pattern NAME_TIME = Pattern.compile("(?i)CTRLPRIX_(\\d{8}_\\d{6})_.*");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
    private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    /** Zmiana cen do wprowadzenia; {@code title}: dostawca i dokument. */
    public record File(String fileName, int lineCount, String title) {
    }

    private PriceControlMacro() {
    }

    /** Czas zapisu z nazwy pliku (czas sklepu) albo {@code null}: bez niego makro nie znajdzie pliku w historii. */
    public static LocalDateTime time(String fileName) {
        Matcher match = NAME_TIME.matcher(fileName == null ? "" : fileName);
        if (!match.matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(match.group(1), TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code files} od najstarszego, każdy z czasem w nazwie. */
    public static String build(List<File> files) {
        List<String> out = new ArrayList<>();
        out.add("# Wprowadzenie zmian cen: " + files.size() + " (odtwarzacz od wersji 0.60)");
        out.add(ReportSaveMacro.OPEN_LINE);
        for (int i = 0; i < files.size(); i++) {
            steps(out, files.get(i), i + 1, files.size());
        }
        out.add("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    private static void steps(List<String> out, File file, int number, int count) {
        LocalDateTime time = time(file.fileName());
        if (time == null) {
            throw new IllegalArgumentException("Nazwa zmiany cen bez daty: " + file.fileName());
        }
        String skip = "zmiana-cen-" + number;
        String empty = skip + "-bez-roznic";
        String label = (LABEL_TIME.format(time) + ", cen: " + file.lineCount() + ", " + file.title())
                .replaceAll("[|\\r\\n]", " ");
        out.addAll(List.of(
                "# " + number + "/" + count + ": " + label,
                "WEBENSURE|" + WINDOW + "|" + CONTROL_MENU + "|" + TARIFF_TAB,
                "WEBOPEN|" + WINDOW + "|" + CONTROL_MENU + "|" + DOWNLOAD_MENU + "|" + HISTORY,
                "WEBWAIT|" + WINDOW + "|" + HISTORY + "||" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + HISTORY,
                "WEBWAIT|" + WINDOW + "|" + HISTORY_BACK + "||" + PAGE_TIMEOUT_MS,
                "WEBROWBUTTON|" + WINDOW + "|" + HISTORY_GRID + "|" + rowPattern(time) + "|" + PROCESS + "|" + skip + "|"
                        + label + "|" + NEXT_PAGE + "|" + HISTORY_PAGES + "|" + PAGE_TIMEOUT_MS,
                "WEBWAIT|" + DIALOG + "|Oui|" + PROCESS_TEXT + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + DIALOG + "|Oui",
                "WINCLOSEALL|" + PDF_WINDOW + "|" + WORK_TIMEOUT_MS,
                "WEBENSURE|" + WINDOW + "|" + CONTROL_MENU + "|" + TARIFF_TAB,
                "WEBOPEN|" + WINDOW + "|" + CONTROL_MENU + "|" + DIFFERENT_PRICES_MENU + "|" + SELECT_ALL,
                "WEBWAIT|" + WINDOW + "|" + SELECT_ALL + "||" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + SELECT_ALL,
                "WEBGRIDWAIT|" + WINDOW + "|" + ALIGN_MSI + "|page|" + SELECTED_PAGE + "|" + empty
                        + "|Różne ceny bez artykułów: " + label + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + ALIGN_MSI,
                "WEBDIALOG|" + ALERT_WINDOW + "|" + ALERT_TEXT + "|Fermer|" + ALERT_MS,
                "WEBGRIDWAIT|" + WINDOW + "|" + ALIGN_MSI + "|page|" + ALIGNED_PAGE + "|" + empty + "||" + WORK_TIMEOUT_MS,
                "LABEL|" + empty,
                "LABEL|" + skip));
    }

    /** Wiersz historii: data oraz godzina i minuta z nazwy pliku albo minuta później. Bez {@code |}, bo dzieli linię. */
    private static String rowPattern(LocalDateTime time) {
        String date = String.format("(?<!\\d)%02d[./-]%02d[./-](?:%02d)?%02d(?!\\d)", time.getDayOfMonth(),
                time.getMonthValue(), time.getYear() / 100, time.getYear() % 100);
        int last = time.getMinute() % 10;
        String minute = time.getMinute() / 10 + (last < 9 ? "[" + last + (last + 1) + "]" : "9");
        String clock = String.format("(?<!\\d)%02d:", time.getHour()) + minute + "(?!\\d)";
        return "i)^(?=.*" + date + ")(?=.*" + clock + ")";
    }
}
