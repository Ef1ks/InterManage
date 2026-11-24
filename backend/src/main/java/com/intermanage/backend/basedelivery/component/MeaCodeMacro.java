package com.intermanage.backend.basedelivery.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro kodów MEA składane przez serwer (agent sklepu wykonuje tylko makra z serwera): te same polecenia co
 * {@code buildMeaMacro} w {@code mercalys-macro.ts}; zmiana ekranów Mercalys = zmiana w obu miejscach.
 *
 * <p>Dla każdego kodu filtr Nazwa Cad. w Liście Cadencier (ca-e064) i po kolei każdy plik z kodem: Przetworzenie,
 * Wybór, Zatwierdź, Integracja, Integracja, Tak, zamknięcie PDF-a, Powrót. Zintegrowany plik znika z listy, więc makro
 * bierze zawsze pierwszy widoczny, aż lista jest pusta. Wymaga odtwarzacza od 0.41.
 */
public final class MeaCodeMacro {

    private static final String WINDOW = "Mercalys";
    private static final String PROGRAM_WINDOW = ReportSaveMacro.PROGRAM_WINDOW;
    private static final String DIALOG = "Les Mousquetaires";
    private static final String PDF_WINDOW = "ahk_class AcrobatSDIWindow";
    private static final String GRID = "dgDetail";
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int INTEGRATION_TIMEOUT_MS = 120000;
    private static final int LATE_PDF_MS = 8000;
    private static final int FILTER_CHECK_MS = 300;
    /** Pod jednym kodem bywa 1-3 plików cadencier; tyle razy makro bierze najwyżej pierwszy plik z listy. */
    static final int MAX_FILES = 5;

    private static final String CADENCIER_TAB = "mnTp_CADENCIER";
    private static final String LIST_MENU_ITEM = "mnTv_CADENCIER_ca-e064_ListeCadenciers";
    private static final String NAME_FILTER = "txtZ1_LibelleCadencier_TxtBox";
    private static final String ROW_BUTTON = "ibtC11_Traitement";
    /** „Wybór”; obok jest „Wybór wysz.” ({@code btnB11_SelectionRecherche}): nie ten. */
    private static final String SELECTION = "btnB3_Select_LnkBouton";
    private static final String APPROVE_SELECTION = "btnB2_Valider_LnkBouton";
    private static final String INTEGRATION = "btnB8_Integration_LnkBouton";
    private static final String INTEGRATE = "btnB2_Integrer_LnkBouton";
    private static final String INTEGRATE_TEXT = "integracj";
    private static final String BACK = "btnB1_Retour_LnkBouton";

    /** Kod trafia do linii makra i do wyrażenia regularnego, więc tylko litery i cyfry. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,20}");

    private MeaCodeMacro() {
    }

    public static boolean isCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    public static String build(List<String> codes) {
        List<String> out = new ArrayList<>();
        out.add("# Kody MEA: " + codes.size());
        out.add(ReportSaveMacro.OPEN_LINE);
        out.add("WEBENSURE|" + WINDOW + "|" + LIST_MENU_ITEM + "|" + CADENCIER_TAB);
        out.add("WEBCLICK|" + WINDOW + "|" + LIST_MENU_ITEM);
        out.add("WEBWAIT|" + WINDOW + "|" + NAME_FILTER + "||" + PAGE_TIMEOUT_MS);
        for (String code : codes) {
            if (!isCode(code)) {
                throw new IllegalArgumentException("Niedozwolony kod MEA: " + code);
            }
            String label = "mea-" + code;
            out.add("# Kod MEA " + code);
            for (int file = 1; file <= MAX_FILES; file++) {
                String typed = label + "-" + file;
                String retyped = typed + "-again";
                // Pole filtra ma inny kod = wpisz; ma ten kod = przeskok za wpisywanie.
                String otherCode = "WEBIFVALUE|" + WINDOW + "|" + NAME_FILTER + "|^(?!" + code + "$)|";
                String typeCode = "WEBKEY|" + WINDOW + "|" + NAME_FILTER + "|^a" + code + "{Enter}";
                out.addAll(List.of(
                        "# Plik " + file,
                        // Raport poprzedniego pliku potrafi otworzyć się dopiero teraz: zamykamy go przed klawiszami.
                        "WINCLOSEALL|" + PDF_WINDOW + "|0",
                        otherCode + typed + "|" + FILTER_CHECK_MS,
                        typeCode,
                        // PDF, który wyskoczył w trakcie wpisywania, zabrał klawisze: zamykamy i wpisujemy jeszcze raz.
                        otherCode + typed + "|" + FILTER_CHECK_MS,
                        "WINCLOSEALL|" + PDF_WINDOW + "|" + LATE_PDF_MS,
                        otherCode + retyped + "|" + FILTER_CHECK_MS,
                        typeCode,
                        "LABEL|" + retyped,
                        "LABEL|" + typed,
                        "WEBIFROW|" + WINDOW + "|" + NAME_FILTER + "|" + GRID + "|" + ROW_BUTTON + "|1|" + code + "|"
                                + label + "|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|%ROW%",
                        "WEBWAIT|" + WINDOW + "|" + SELECTION + "||" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|" + SELECTION,
                        "WEBWAIT|" + WINDOW + "|" + APPROVE_SELECTION + "||" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|" + APPROVE_SELECTION,
                        "WEBWAIT|" + WINDOW + "|" + INTEGRATION + "||" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|" + INTEGRATION,
                        "WEBWAIT|" + WINDOW + "|" + INTEGRATE + "||" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|" + INTEGRATE,
                        "WEBWAIT|" + DIALOG + "|Oui|" + INTEGRATE_TEXT + "|" + PAGE_TIMEOUT_MS,
                        "WEBCLICK|" + DIALOG + "|Oui",
                        // Raport otwiera się po integracji (przy dużym pliku ponad minutę); dopiero potem Powrót.
                        "WINCLOSEALL|" + PDF_WINDOW + "|" + INTEGRATION_TIMEOUT_MS,
                        "WEBWAIT|" + WINDOW + "|" + BACK + "||" + INTEGRATION_TIMEOUT_MS,
                        "WEBCLICK|" + WINDOW + "|" + BACK,
                        "WEBWAIT|" + WINDOW + "|" + NAME_FILTER + "||" + PAGE_TIMEOUT_MS));
            }
            out.add("LABEL|" + label);
        }
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }
}
