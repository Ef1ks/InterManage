package com.intermanage.backend.basedelivery.component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makra wprowadzenia dostawy z bazy do Mercalys na komputerze sklepu (odtwarzacz od 0.66), po „Uzupełnij ITM8”:
 *
 * <ul>
 * <li>{@link #receive}: plik bonu każdego dostawcy (538, 88110) z Obsługi BL bazy → Integracja → zamówienie z bazy
 * tego dostawcy z datą dostawy dziś, najbliższą wcześniejszą albo późniejszą, bez niego pusty kod → pobranie → błędy (tylko
 * „Niezgodne jednostki”, „art. nieistniejący” i „art. już doręczone”, inne zatrzymują makro) → wyczyszczenie błędów; na końcu Obsługa BL nie
 * może mieć pliku tego bonu, a każde zamówienie daje „Bon dostawy Excel” do folderu, skąd agent wysyła go na serwer;
 * <li>{@link #close}: po sprawdzeniu różnic Nr BL w nagłówku zamówień i zakończenie dostaw, jak przy fakturach
 * ({@code buildDeliveryMacro} w {@code mercalys-macro.ts}).
 * </ul>
 *
 * Numery zamówień wracają w wyniku makra jako {@code dostawca=numer}, zakończone jako {@code zamkniete=numer}. Makro
 * można powtórzyć: plik, który już wszedł, jest pomijany, a jego zamówienie ({@code known}) serwer podaje sam.
 */
public final class BaseReceiveMacro {

    private static final String WINDOW = "Mercalys";
    private static final String PROGRAM_WINDOW = ReportSaveMacro.PROGRAM_WINDOW;
    private static final String DIALOG = "Les Mousquetaires";
    /** Komunikat (BoiteAlerte.aspx) nie ma tytułu „Les Mousquetaires”. */
    private static final String ALERT_WINDOW = "ahk_class Internet Explorer_TridentDlgFrame";
    private static final String PDF_WINDOW = "ahk_class AcrobatSDIWindow";
    private static final String GRID = "dgDetail";
    private static final String ANY_PAGE = "webaviso";
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int BL_TIMEOUT_MS = 60000;
    /** Pobranie dużego bonu do zamówienia. */
    private static final int IMPORT_TIMEOUT_MS = 300000;
    private static final int PDF_TIMEOUT_MS = 30000;
    private static final int LATE_PDF_MS = 8000;
    private static final int RELOAD_MS = 1500;
    /** Po wyczyszczeniu błędów Mercalys potrzebuje chwili, zanim pokaże nową listę plików. */
    private static final int AFTER_CLEAR_MS = 3000;
    private static final int EXCEL_TIMEOUT_MS = 180000;

    private static final String PURCHASES_TAB = "mnTp_ACHAT";
    private static final String DELIVERIES = "mnTv_ACHAT_Livraison";
    private static final String ORDERS = "mnTv_ACHAT_Commande";
    private static final String ORDER_ENTRY = "mnTv_ACHAT_ac-e011_SaisieCommandeCompleteCmd";

    // Obsługa BL bazy (ac-e034) i linie bonu (ac-e080), jak w Itm8Macro.
    private static final String BL_MENU_ITEM = "mnTv_ACHAT_ac-e034_BlEuropa";
    private static final String BL_FILES_HEADING = "Lista PZ do przetwarzania";
    private static final String BL_SELECTION = "btnSelection_LnkBouton";
    private static final String BL_INTEGRATION = "btnB3_Integration_LnkBouton";
    private static final String BL_LINES_GRID = "dgArticles";
    private static final String BL_LINE_ERROR_TEXT = "ITM nie istnieje";
    private static final String BL_BON_COLUMN = "4";
    private static final String NEXT_PAGE = "pgrDetail_ibtSuivant";
    private static final String FIRST_PAGE = "pgrDetail_ibtPremier";
    private static final String PAGE_LIST = "pgrDetail_ddlGoTo";
    /** „Wystąpiły błędy… Te linie nie zostaną pobrane…” albo, bez błędów, „Chcesz uruchomic integracje art. z PZ”. */
    private static final String INTEGRATION_TEXT = "Te linie nie;integracj";

    // Przesył dostaw: zamówienia oczekujące i wybrane.
    private static final String ARROW_IMAGE = "ibtB1_Ajouter_ImageButton1";
    private static final String ARROW_LINK = "ibtB1_Ajouter_LnkBouton";
    private static final String CREATE_CODE = "btnB5_CreationCde_LnkBouton";
    private static final String UNLOAD = "btnB6_Decharger_LnkBouton";
    private static final String ORIGIN = "Base;Baza";
    /** „Czy chciałbyś pobrać”, a czasem też „Te linie nie zostaną pobrane…” albo pytanie o integrację. */
    private static final String UNLOAD_TEXT = "pobra;integracj";
    /** Najwięcej pytań po Zatwierdź, zanim przyjdzie komunikat z liczbą zaimportowanych. */
    private static final int UNLOAD_QUESTIONS = 3;
    /** Każdy komunikat z samym Zamknij, po adresie okna: liczby i treść („0 artykuł(y) zaimportowany(e) - …”) są różne. */
    private static final String ANY_ALERT = "BoiteAlerte";
    private static final String FIX_ERRORS_TEXT = "popraw";

    // Wybór dostawcy pustego kodu (ac-e050).
    private static final String SUPPLIER_CODE = "txeZ1_Code_TxtBox";
    private static final String SUPPLIER_APPROVE = "btnB2_Valider_LnkBouton";
    private static final String EMPTY_ORDER_TEXT = "puste";

    // Lista błędów pobrania (ac-e051).
    private static final String ERRORS_PAGE = "ac-e051";
    private static final String ERRORS_DELETE = "btnB2_Supprimer_LnkBouton";
    private static final String ERRORS_BACK = "btnB1_Retour_LnkBouton";
    private static final String ALLOWED_ERRORS = "niezgodne jednost;nieistniej;art.ju;doręczone";
    private static final String CLEAR_ERRORS_TEXT = "wyczy";

    // Wprowadzanie zamówień (ac-e011): wydruki, nagłówek i zakończenie.
    private static final String CRITERION = "lstZ1_Critere_TxtBox";
    /** 3 = oczekuje na dostawę; lista przeładowuje się po prawdziwych klawiszach. */
    private static final String CRITERION_KEYS = "3{Enter}";
    private static final int NUMBER_COLUMN = 1;
    private static final int IDENTIFICATION_COLUMN = 12;
    private static final String HEADER = "btnB11_Entete_LnkBouton";
    private static final String HEADER_FIELD = "txtZ16_IdentificationCde_TxtBox";
    private static final String HEADER_SAVE = "btnB4_Valider_LnkBouton";
    private static final String HEADER_TEXT = "zachowa";
    private static final String CLOSE = "btnB3_Cloturer_LnkBouton";
    private static final String CLOSE_TEXT = "zamkn";
    private static final String CLOSE_PRINT = "chkP1_BonValo_CheckBox1";
    private static final String CLOSE_APPROVE = "btnP1_Valider_LnkBouton";
    private static final String PRINT = "btnB9_Imprimer_LnkBouton";
    private static final String NOTE_EXCEL = "lkbL7_BLExcel_LinkAviso";

    private static final Pattern EXCEL_LINE = Pattern.compile("(?m)^EXCELSAVE\\|([^|\\r\\n]+_(\\d+)\\.xlsx)\\|");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** Dostawca pliku bonu z nazwą z listy dostawców (po niej makro rozpoznaje zamówienie). */
    public record Supplier(String code, String name) {

        /** Nazwa bez znaków, które dzielą linię makra. */
        String macroName() {
            return name.replaceAll("[|;\\r\\n]", " ").strip();
        }

        /** Pierwsze słowo nazwy z samych liter, do wyrażenia regularnego. */
        String firstWord() {
            Matcher matcher = Pattern.compile("[A-Za-z]{3,}").matcher(name);
            return matcher.find() ? matcher.group() : "";
        }
    }

    /** Plik, który makro zapisze, a agent wyśle: dostawca i pełna ścieżka. */
    public record NoteFile(String supplierCode, String path) {
    }

    /** Zamówienie do zakończenia. */
    public record Order(String supplierCode, String orderNumber) {
    }

    private BaseReceiveMacro() {
    }

    /**
     * @param known zamówienia z wcześniejszego przebiegu (dostawca → numer), niezakończone
     * @param today dzień robienia dostawy (czas sklepu)
     * @param folder folder na bony z Mercalys
     */
    public static String receive(String bonNumber, List<Supplier> suppliers, Map<String, String> known,
            LocalDate today, String folder) {
        String bon = Itm8Macro.key(bonNumber);
        List<String> openFiles = List.of(
                "WEBENSURE|" + WINDOW + "|" + DELIVERIES + "|" + PURCHASES_TAB,
                "WEBOPEN|" + WINDOW + "|" + DELIVERIES + "|" + BL_MENU_ITEM,
                "SLEEP|" + RELOAD_MS,
                "WEBWAIT|" + WINDOW + "|" + BL_SELECTION + "|" + BL_FILES_HEADING + "|" + PAGE_TIMEOUT_MS);
        List<String> out = new ArrayList<>();
        out.add("# Wprowadź dostawę z bazy: bon " + bonNumber);
        out.add(ReportSaveMacro.OPEN_LINE);
        known.forEach((supplier, order) -> out.add("WEBORDERKNOWN|" + supplier + "|" + order));
        out.addAll(openFiles);
        for (Supplier supplier : suppliers) {
            String code = supplier.code();
            String none = "brak-" + code;
            String panel = "zamowienia-" + code;
            String create = "nowy-kod-" + code;
            String read = "numer-" + code;
            String errors = "bledy-" + code;
            String question = "pytanie-" + code;
            String secondQuestion = "drugie-pytanie-" + code;
            String imported = "komunikat-" + code;
            String noPanel = "bez-panelu-" + code;
            String unloaded = "pobrane-" + code;
            String done = "koniec-" + code;
            out.add("# Plik bonu " + bonNumber + " od dostawcy " + code);
            out.add("WEBPICKRE|" + WINDOW + "|" + GRID + "|" + fileRow(bon, code) + "|" + PAGE_TIMEOUT_MS + "|" + none);
            out.add("WEBCLICK|" + WINDOW + "|" + BL_SELECTION);
            out.add("WEBWAIT|" + WINDOW + "|" + BL_INTEGRATION + "||" + BL_TIMEOUT_MS);
            // Sprawdza, że otwarte linie są z tego bonu, i dopisuje do wyniku linie bez ITM8.
            out.add("WEBCOLLECT|" + WINDOW + "|" + BL_LINES_GRID + "|" + BL_LINE_ERROR_TEXT + "|0|" + NEXT_PAGE + "|"
                    + PAGE_LIST + "|1|" + BL_BON_COLUMN + "|" + bonNumber);
            out.add("WEBCLICK|" + WINDOW + "|" + BL_INTEGRATION);
            // Pytanie o błędne linie albo o uruchomienie integracji (czasem jedno po drugim): Tak i dalej. Zamiast panelu może
            // też przyjść komunikat „0 artykuł(y) zaimportowany(e) - 450 błąd(błędy)”: Zamknij.
            String importedAlert = "|" + ALERT_WINDOW + "|" + ANY_ALERT + "|Fermer|" + imported;
            out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + CREATE_CODE + "|" + IMPORT_TIMEOUT_MS + "|" + DIALOG
                    + "|" + INTEGRATION_TEXT + "|Oui|" + question + importedAlert);
            out.add("WEBGOTO|" + panel);
            out.add("LABEL|" + question);
            out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + CREATE_CODE + "|" + IMPORT_TIMEOUT_MS + "|" + DIALOG
                    + "|" + INTEGRATION_TEXT + "|Oui|" + secondQuestion + importedAlert);
            out.add("WEBGOTO|" + panel);
            out.add("LABEL|" + secondQuestion);
            out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + CREATE_CODE + "|" + IMPORT_TIMEOUT_MS + importedAlert);
            out.add("WEBGOTO|" + panel);
            out.add("LABEL|" + imported);
            // Po komunikacie: pytanie o poprawienie błędów, lista błędów, panel zamówień albo od razu Obsługa BL.
            out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + CREATE_CODE + "," + BL_SELECTION + "," + ERRORS_DELETE
                    + "|" + BL_TIMEOUT_MS + "|" + DIALOG + "|" + FIX_ERRORS_TEXT + "|Oui|" + errors);
            out.add("WEBIFSEEN|" + WINDOW + "|" + CREATE_CODE + "|" + noPanel + "|1000");
            out.add("WEBGOTO|" + panel);
            out.add("LABEL|" + noPanel);
            out.add("WEBIFSEEN|" + WINDOW + "|" + BL_SELECTION + "|" + errors + "|1000");
            out.add("WINCLOSEALL|" + PDF_WINDOW + "|" + LATE_PDF_MS);
            out.add("WEBGOTO|" + done);
            out.add("LABEL|" + panel);
            out.add("WEBWAIT|" + WINDOW + "|" + CREATE_CODE + "||" + BL_TIMEOUT_MS);
            out.add("WEBORDERPICK|" + WINDOW + "|" + ARROW_IMAGE + "," + ARROW_LINK + "|" + code + "|"
                    + supplier.macroName() + "|" + ORIGIN + "|" + today.format(DAY) + "|" + create + "|"
                    + PAGE_TIMEOUT_MS);
            out.add("WEBGOTO|" + read);
            out.add("LABEL|" + create);
            out.add("WEBCLICK|" + WINDOW + "|" + CREATE_CODE);
            out.add("WEBWAIT|" + WINDOW + "|" + SUPPLIER_CODE + "||" + PAGE_TIMEOUT_MS);
            out.add("WEBKEY|" + WINDOW + "|" + SUPPLIER_CODE + "|^a" + code + "{Enter}");
            out.add("SLEEP|" + RELOAD_MS);
            out.add("WEBPICKRE|" + WINDOW + "|" + GRID + "|(?<!\\d)0*" + code + "(?!\\d).*" + supplier.firstWord() + "|"
                    + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + WINDOW + "|" + SUPPLIER_APPROVE);
            out.add("WEBWAIT|" + DIALOG + "|Oui|" + EMPTY_ORDER_TEXT + "|" + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + DIALOG + "|Oui");
            out.add("SLEEP|" + RELOAD_MS);
            out.add("WEBWAIT|" + WINDOW + "|" + CREATE_CODE + "||" + BL_TIMEOUT_MS);
            out.add("LABEL|" + read);
            out.add("WEBORDERREAD|" + WINDOW + "|" + ARROW_IMAGE + "|" + code + "|" + supplier.macroName() + "|"
                    + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + WINDOW + "|" + UNLOAD);
            // Po Zatwierdź pytania (Tak), a po chwili komunikat: „Suma zaimportowanych zamówień” albo „0 artykuł(y)
            // zaimportowany(e) - 450 błąd(błędy)” (Zamknij). Pytanie o poprawienie błędów przychodzi dopiero po nim.
            for (int number = 1; number <= UNLOAD_QUESTIONS; number++) {
                String next = "po-pytaniu-" + number + "-" + code;
                out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + BL_SELECTION + "," + ERRORS_DELETE + "|"
                        + IMPORT_TIMEOUT_MS + "|" + DIALOG + "|" + UNLOAD_TEXT + "|Oui|" + next + "|" + ALERT_WINDOW + "|"
                        + ANY_ALERT + "|Fermer|" + unloaded + "|" + DIALOG + "|" + FIX_ERRORS_TEXT + "|Oui|"
                        + errors);
                out.add("WEBGOTO|" + unloaded);
                out.add("LABEL|" + next);
            }
            out.add("WEBALERT|" + ALERT_WINDOW + "|" + ANY_ALERT + "|Fermer|" + IMPORT_TIMEOUT_MS);
            out.add("LABEL|" + unloaded);
            // Bez błędów od razu Obsługa BL i dwa PDF-y; z błędami pytanie o poprawienie i lista błędów.
            out.add("WEBPAGE|" + WINDOW + "|" + ANY_PAGE + "|" + BL_SELECTION + "," + ERRORS_DELETE + "|"
                    + IMPORT_TIMEOUT_MS + "|" + DIALOG + "|" + FIX_ERRORS_TEXT + "|Oui|" + errors);
            out.add("WEBIFSEEN|" + WINDOW + "|" + BL_SELECTION + "|" + errors + "|1000");
            out.add("WINCLOSEALL|" + PDF_WINDOW + "|" + PDF_TIMEOUT_MS);
            out.add("WEBGOTO|" + done);
            out.add("LABEL|" + errors);
            out.add("WINCLOSEALL|" + PDF_WINDOW + "|" + PDF_TIMEOUT_MS);
            out.add("WEBPAGE|" + WINDOW + "|" + ERRORS_PAGE + "|" + ERRORS_DELETE + "|" + BL_TIMEOUT_MS);
            out.add("WINCLOSEALL|" + PDF_WINDOW + "|" + LATE_PDF_MS);
            out.add("WEBERRORS|" + WINDOW + "|" + ERRORS_DELETE + "|" + ALLOWED_ERRORS + "|" + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + WINDOW + "|" + ERRORS_BACK);
            out.add("WEBWAIT|" + DIALOG + "|Oui|" + CLEAR_ERRORS_TEXT + "|" + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + DIALOG + "|Oui");
            out.add("SLEEP|" + AFTER_CLEAR_MS);
            out.add("LABEL|" + done);
            out.addAll(openFiles);
            out.add("LABEL|" + none);
        }
        out.add("WEBNOROW|" + WINDOW + "|" + GRID + "|(?<!\\d)0*" + bon + "(?!\\d)|W Obsłudze BL bazy został plik bonu "
                + bonNumber + ", sprawdź go w Mercalys.|" + PAGE_TIMEOUT_MS);
        out.add("WEBORDERS|W Obsłudze BL bazy nie ma pliku bonu " + bonNumber
                + ", a aplikacja nie zna numeru zamówienia. Jeśli bon jest już wprowadzony, wpisz numer zamówienia w oknie.");
        for (Supplier supplier : suppliers) {
            String label = "bez-bonu-" + supplier.code();
            out.add("# Bon dostawy Excel dostawcy " + supplier.code());
            out.add("WEBIFORDER|" + supplier.code() + "|" + label);
            out.addAll(openOrders());
            out.add("WEBORDERROW|" + WINDOW + "|" + GRID + "|" + NUMBER_COLUMN + "|" + supplier.code() + "|"
                    + supplier.macroName() + "|" + FIRST_PAGE + "|" + NEXT_PAGE);
            out.add("WEBCLICK|" + WINDOW + "|" + PRINT);
            out.add("SLEEP|" + (RELOAD_MS + 1000));
            out.add("WEBWAIT|" + WINDOW + "|" + NOTE_EXCEL + "||" + PAGE_TIMEOUT_MS);
            out.add("WEBCLICK|" + WINDOW + "|" + NOTE_EXCEL);
            out.add("EXCELSAVE|" + notePath(folder, bonNumber, supplier.code()) + "|" + EXCEL_TIMEOUT_MS);
            out.add("LABEL|" + label);
        }
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    /** Nr BL w nagłówku zamówień i zakończenie dostaw (zatwierdza je zlecający w przeglądarce). */
    public static String close(String bonNumber, List<Order> orders) {
        List<String> out = new ArrayList<>();
        out.add("# Zakończ dostawę z bazy: bon " + bonNumber);
        out.add(ReportSaveMacro.OPEN_LINE);
        orders.forEach(order -> out.add("WEBRESULT|" + order.orderNumber() + "|" + order.supplierCode() + "|"
                + bonNumber));
        out.addAll(openOrders());
        out.add("WEBHEADERS|" + WINDOW + "|" + GRID + "|" + NUMBER_COLUMN + "|" + IDENTIFICATION_COLUMN + "|" + HEADER
                + "|" + HEADER_FIELD + "|" + HEADER_SAVE + "|" + DIALOG + "|" + HEADER_TEXT + "|Oui|" + FIRST_PAGE + "|"
                + NEXT_PAGE);
        out.add("WEBCLOSE|" + WINDOW + "|" + GRID + "|" + NUMBER_COLUMN + "|" + IDENTIFICATION_COLUMN + "|" + CLOSE
                + "|" + DIALOG + "|" + CLOSE_TEXT + "|Oui|" + CLOSE_PRINT + "|" + CLOSE_APPROVE + "|" + FIRST_PAGE + "|"
                + NEXT_PAGE);
        out.add("WINCLOSEALL|" + PDF_WINDOW + "|20000");
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    /** Pliki, które makro zapisze (z jego linii EXCELSAVE). */
    public static List<NoteFile> noteFiles(String macro) {
        return EXCEL_LINE.matcher(macro).results().map(match -> new NoteFile(match.group(2), match.group(1))).toList();
    }

    static String notePath(String folder, String bonNumber, String supplierCode) {
        return folder.strip().replaceAll("\\\\+$", "") + "\\BON_" + Itm8Macro.key(bonNumber) + "_" + supplierCode
                + ".xlsx";
    }

    /** Wiersz pliku bonu dostawcy: nazwa {@code 095_<nr bonu>_00538}, jak w {@link Itm8Macro#collect}. */
    private static String fileRow(String bon, String supplierCode) {
        return "(?<!\\d)0*" + bon + "\\D+0*" + supplierCode + "(?!\\d)";
    }

    private static List<String> openOrders() {
        return List.of(
                "WEBENSURE|" + WINDOW + "|" + ORDERS + "|" + PURCHASES_TAB,
                "WEBOPEN|" + WINDOW + "|" + ORDERS + "|" + ORDER_ENTRY + "|" + CRITERION,
                "SLEEP|" + RELOAD_MS,
                "WEBWAIT|" + WINDOW + "|" + CRITERION + "||" + PAGE_TIMEOUT_MS,
                // Spóźniony PDF zabrałby klawisze.
                "WINCLOSEALL|" + PDF_WINDOW + "|0",
                "WEBKEY|" + WINDOW + "|" + CRITERION + "|" + CRITERION_KEYS,
                "SLEEP|" + RELOAD_MS,
                "WEBWAIT|" + WINDOW + "|" + HEADER + "||" + PAGE_TIMEOUT_MS);
    }
}
