package com.intermanage.backend.basedelivery.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.intermanage.backend.basedelivery.dto.BaseDeliveryLineResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makra ITM8 dostawy z bazy, składane przez serwer (agent sklepu wykonuje tylko makra z serwera). Te same polecenia co
 * w przeglądarce ({@code buildMissingItm8Macro}, {@code buildItm8Macro} w {@code mercalys-macro.ts}); zmiana ekranów
 * Mercalys = zmiana w obu miejscach.
 *
 * <ul>
 * <li>{@link #collect}: „Zbierz brakujące ITM8”, Obsługa BL bazy, plik bonu od każdego dostawcy, kody z błędem
 * „ITM nie istnieje” ze wszystkich stron (odtwarzacz kładzie je do Schowka i do pliku wyniku).</li>
 * <li>{@link #apply}: „Uzupełnij ITM8”, nowy kod ITM8 na karcie artykułu (dostawca, ref. dostawcy, opakowanie,
 * zamówienie min., data usunięcia, nieaktywny, cena zakupu), każde pole sprawdzone przed zapisem.</li>
 * </ul>
 *
 * Pierwsza linia to {@link ReportSaveMacro#OPEN_LINE}; hasło Mercalys dokłada agent ze swoich ustawień.
 */
public final class Itm8Macro {

    private static final String WINDOW = "Mercalys";
    private static final String PROGRAM_WINDOW = ReportSaveMacro.PROGRAM_WINDOW;
    private static final String DIALOG = "Les Mousquetaires";
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final String PURCHASES_TAB = "mnTp_ACHAT";
    private static final String DELIVERIES = "mnTv_ACHAT_Livraison";

    // Obsługa BL bazy (ac-e034) i linie bonu (ac-e080).
    private static final String BL_MENU_ITEM = "mnTv_ACHAT_ac-e034_BlEuropa";
    private static final String BL_FILES_HEADING = "Lista PZ do przetwarzania";
    private static final String BL_SELECTION = "btnSelection_LnkBouton";
    private static final String BL_DETAIL_SCREEN = "btnB3_Integration_LnkBouton";
    private static final String BL_FILES_GRID = "dgDetail";
    private static final String BL_LINES_GRID = "dgArticles";
    private static final String BL_BON_COLUMN = "4";
    private static final String MISSING_ITM8_TEXT = "ITM nie istnieje";
    private static final String BL_ITM8_COLUMN = "0";
    private static final String BL_NEXT_PAGE = "pgrDetail_ibtSuivant";
    private static final String BL_PAGE_LIST = "pgrDetail_ddlGoTo";
    private static final String BL_STOP_ON_EMPTY_PAGE = "1";
    private static final String BL_BACK = "btnB1_Retour_LnkBouton";
    private static final int BL_TIMEOUT_MS = 60000;
    private static final int BL_REOPEN_MS = 1500;
    private static final List<String> BASE_FILE_SUPPLIERS = List.of("538", "88110");

    // Karta artykułu (ar-e001) i „Kod ITM8 :” (ar-e006).
    private static final String ARTICLE_TAB = "mnTp_ARTICLE";
    private static final String ARTICLE_MENU_ITEM = "mnTv_ARTICLE_ar-e001_FicheArticle";
    private static final String EAN_FIELD = "txtZ4_CodeEAN_TxtBox";
    private static final String ITM8_FIELD = "txtZ1_CodeITM_TxtBox";
    private static final String ITM8_BUTTON = "lnkZ1_CodeITM_LnkAviso";
    private static final String NEW_ITM8_FIELD = "txtZ02_Itm_TxtBox";
    private static final String SUPPLIER_FIELD = "lstZ04_Fournisseur_TxtBox";
    private static final String SUPPLIER_REF_FIELD = "txtZ06_RefFrs_TxtBox";
    private static final String MIN_ORDER_FIELD = "txnZ08_MinCde_TxtBox";
    private static final String PACK_FIELD = "txnZ07_Conditionnement_TxtBox";
    private static final String DELETE_DATE_FIELD = "txdZ09_DateSuppr_TxtBox";
    private static final String ACTIVE_CHECKBOX = "chkZ10_Actif_CheckBox1";
    private static final String NET_PRICE_FIELD = "txtZ14_PANet_TxtBox";
    private static final String APPROVE = "btnB4_Valider_LnkBouton";
    private static final String CREATE_TEXT = "Utworzy";
    private static final String BACK = "btnB1_Retour_LnkBouton";
    private static final String RESET = "btnB2_RAZ_LnkBouton";
    private static final int AFTER_CONFIRM_MS = 1500;
    private static final int AFTER_UNCHECK_MS = 500;

    /** Data usunięcia ITM8 = data dokumentu + tyle lat. */
    public static final int DELETE_AFTER_YEARS = 2;

    /** Pozycja makra „Uzupełnij ITM8”: linia dokumentu z kodem z listy. */
    public record Item(String itm8, String ean, String supplierCode, BigDecimal pack, LocalDate deleteDate,
            BigDecimal purchasePrice, String name) {
    }

    public record Skipped(String code, String reason) {
    }

    public record Plan(List<Item> items, List<Skipped> skipped) {
    }

    private Itm8Macro() {
    }

    /** Kody z tekstu (nowa linia, przecinek, średnik, spacja, tabulator), bez zer wiodących i powtórzeń. */
    public static List<String> parseCodes(String text) {
        Set<String> codes = new LinkedHashSet<>();
        if (text != null) {
            for (String raw : text.split("[\\s,;]+")) {
                String key = key(raw);
                if (!key.isEmpty()) {
                    codes.add(key);
                }
            }
        }
        return List.copyOf(codes);
    }

    /**
     * Paruje kody z liniami dokumentu: pierwsza linia z danym ITM8 (ten sam artykuł bywa na kilku nośnikach). Linia
     * bez EAN-u jest pomijana, bo makro szuka karty artykułu po EAN-ie. Dostawca linii z odpowiedzi (alkohol 88110).
     */
    public static Plan plan(BaseDeliveryResponse delivery, List<String> codes) {
        Map<String, BaseDeliveryLineResponse> lines = new LinkedHashMap<>();
        for (BaseDeliveryLineResponse line : delivery.lines()) {
            String key = key(line.itm8());
            if (!key.isEmpty()) {
                lines.putIfAbsent(key, line);
            }
        }
        LocalDate deleteDate = delivery.deliveryDate().plusYears(DELETE_AFTER_YEARS);
        List<Item> items = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : codes) {
            String key = key(code);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            BaseDeliveryLineResponse line = lines.get(key);
            if (line == null) {
                skipped.add(new Skipped(key, "Nie ma w dokumencie"));
            } else if (line.ean() == null || key(line.ean()).isEmpty()) {
                skipped.add(new Skipped(key, "Bez EAN-u w dokumencie"));
            } else {
                items.add(new Item(key, line.ean().strip(), key(line.supplierCode()), line.pack(), deleteDate,
                        line.purchasePrice(), line.name()));
            }
        }
        return new Plan(List.copyOf(items), List.copyOf(skipped));
    }

    /** Dostawcy plików bonu w Obsłudze BL bazy: 538 i 88110 (gdy mają linie), potem inni z dokumentu. */
    public static List<String> fileSuppliers(BaseDeliveryResponse delivery) {
        Set<String> fromDocument = new LinkedHashSet<>();
        delivery.lines().forEach(line -> {
            String code = key(line.supplierCode());
            if (!code.isEmpty()) {
                fromDocument.add(code);
            }
        });
        if (fromDocument.isEmpty()) {
            return BASE_FILE_SUPPLIERS;
        }
        List<String> suppliers = new ArrayList<>();
        BASE_FILE_SUPPLIERS.stream().filter(fromDocument::contains).forEach(suppliers::add);
        fromDocument.stream().filter(code -> !BASE_FILE_SUPPLIERS.contains(code)).forEach(suppliers::add);
        return suppliers;
    }

    /** „Zbierz brakujące ITM8” z pliku bonu każdego dostawcy (wymaga odtwarzacza od 0.45). */
    public static String collect(String bonNumber, List<String> suppliers) {
        String digits = key(bonNumber);
        String waitForFiles = "WEBWAIT|" + WINDOW + "|" + BL_SELECTION + "|" + BL_FILES_HEADING + "|" + PAGE_TIMEOUT_MS;
        List<String> openFiles = List.of(
                "WEBENSURE|" + WINDOW + "|" + DELIVERIES + "|" + PURCHASES_TAB,
                "WEBOPEN|" + WINDOW + "|" + DELIVERIES + "|" + BL_MENU_ITEM,
                "SLEEP|" + BL_REOPEN_MS,
                waitForFiles);
        List<String> out = new ArrayList<>();
        out.add("# Zbierz brakujące ITM8: bon " + bonNumber + ", dostawcy " + String.join(", ", suppliers));
        out.add(ReportSaveMacro.OPEN_LINE);
        for (int index = 0; index < suppliers.size(); index++) {
            String supplier = suppliers.get(index);
            String label = "bon-" + digits + "-" + supplier;
            out.add("# Plik bonu " + bonNumber + " od dostawcy " + supplier);
            if (index == 0) {
                out.addAll(openFiles);
            } else {
                out.add(waitForFiles);
            }
            out.add("WEBPICKRE|" + WINDOW + "|" + BL_FILES_GRID + "|(?<!\\d)0*" + digits + "\\D+0*" + supplier
                    + "(?!\\d)|" + PAGE_TIMEOUT_MS + "|" + label);
            out.add("WEBCLICK|" + WINDOW + "|" + BL_SELECTION);
            out.add("WEBWAIT|" + WINDOW + "|" + BL_DETAIL_SCREEN + "||" + BL_TIMEOUT_MS);
            out.add("WEBCOLLECT|" + WINDOW + "|" + BL_LINES_GRID + "|" + MISSING_ITM8_TEXT + "|" + BL_ITM8_COLUMN + "|"
                    + BL_NEXT_PAGE + "|" + BL_PAGE_LIST + "|" + BL_STOP_ON_EMPTY_PAGE + "|" + BL_BON_COLUMN + "|"
                    + bonNumber);
            out.add("WEBCLICK|" + WINDOW + "|" + BL_BACK);
            out.add(waitForFiles);
            out.addAll(openFiles);
            out.add("LABEL|" + label);
        }
        out.add("WEBCOLLECTED|W Obsłudze BL bazy nie ma pliku bonu " + bonNumber + " od dostawców "
                + String.join(", ", suppliers) + ".");
        out.add("WEBCLICK|" + WINDOW + "|" + PURCHASES_TAB);
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    /** „Uzupełnij ITM8” dla pozycji z {@link #plan} (wymaga odtwarzacza od 0.39). */
    public static String apply(List<Item> items) {
        List<String> out = new ArrayList<>();
        out.add("# Uzupełnij ITM8: " + items.size() + " poz.");
        out.add(ReportSaveMacro.OPEN_LINE);
        out.add("WEBENSURE|" + WINDOW + "|" + ARTICLE_MENU_ITEM + "|" + ARTICLE_TAB);
        out.add("WEBCLICK|" + WINDOW + "|" + ARTICLE_MENU_ITEM);
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            String itm8 = item.itm8().replaceAll("\\D", "");
            String itm8Value = "^0*" + key(itm8) + "$";
            String supplierValue = "^0*" + key(item.supplierCode()) + "$";
            String pack = commaNumber(item.pack(), 0);
            String packValue = "^" + pack + "(,0+)?$";
            String price = commaNumber(item.purchasePrice(), 2);
            String priceValue = "^" + price + "0*$";
            String date = mercalysDate(item.deleteDate());
            String inactive = "nieaktywny-" + (index + 1);
            out.addAll(List.of(
                    "# " + (index + 1) + "/" + items.size() + ": ITM8 " + itm8 + " · EAN " + item.ean(),
                    "WEBWAIT|" + WINDOW + "|" + EAN_FIELD + "||" + PAGE_TIMEOUT_MS,
                    "WEBKEY|" + WINDOW + "|" + EAN_FIELD + "|^a" + item.ean() + "{Enter}",
                    "WEBVALUE|" + WINDOW + "|" + ITM8_FIELD + "|^0*[1-9]\\d*$|" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + WINDOW + "|" + ITM8_BUTTON,
                    "WEBWAIT|" + WINDOW + "|" + NEW_ITM8_FIELD + "||" + PAGE_TIMEOUT_MS,
                    "WEBKEY|" + WINDOW + "|" + NEW_ITM8_FIELD + "|^a" + itm8 + "{Tab}",
                    "WEBVALUE|" + WINDOW + "|" + NEW_ITM8_FIELD + "|" + itm8Value + "|" + PAGE_TIMEOUT_MS,
                    "WEBKEY|" + WINDOW + "|" + SUPPLIER_FIELD + "|^a" + item.supplierCode() + "{Tab}",
                    "WEBVALUE|" + WINDOW + "|" + SUPPLIER_FIELD + "|" + supplierValue + "|" + PAGE_TIMEOUT_MS,
                    "WEBKEY|" + WINDOW + "|" + SUPPLIER_REF_FIELD + "|^a" + itm8 + "{Tab}",
                    "WEBKEY|" + WINDOW + "|" + PACK_FIELD + "|^a" + pack + "{Tab}",
                    "WEBKEY|" + WINDOW + "|" + MIN_ORDER_FIELD + "|^a" + pack + "{Tab}",
                    "WEBKEY|" + WINDOW + "|" + DELETE_DATE_FIELD + "|^a" + date + "{Tab}",
                    "WEBIFVALUE|" + WINDOW + "|" + ACTIVE_CHECKBOX + "|^tak$|" + inactive + "|1000",
                    "WEBCLICK|" + WINDOW + "|" + ACTIVE_CHECKBOX,
                    "SLEEP|" + AFTER_UNCHECK_MS,
                    "LABEL|" + inactive,
                    "WEBKEY|" + WINDOW + "|" + NET_PRICE_FIELD + "|^a" + price + "{Tab}",
                    "WEBVALUE|" + WINDOW + "|" + NEW_ITM8_FIELD + "|" + itm8Value,
                    "WEBVALUE|" + WINDOW + "|" + SUPPLIER_FIELD + "|" + supplierValue,
                    "WEBVALUE|" + WINDOW + "|" + SUPPLIER_REF_FIELD + "|" + itm8Value,
                    "WEBVALUE|" + WINDOW + "|" + PACK_FIELD + "|" + packValue,
                    "WEBVALUE|" + WINDOW + "|" + MIN_ORDER_FIELD + "|" + packValue,
                    "WEBVALUE|" + WINDOW + "|" + DELETE_DATE_FIELD + "|" + datePattern(item.deleteDate()),
                    "WEBVALUE|" + WINDOW + "|" + ACTIVE_CHECKBOX + "|^nie$",
                    "WEBVALUE|" + WINDOW + "|" + NET_PRICE_FIELD + "|" + priceValue,
                    "WEBCLICK|" + WINDOW + "|" + APPROVE,
                    "WEBWAIT|" + DIALOG + "|Oui|" + CREATE_TEXT + "|" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + DIALOG + "|Oui",
                    "SLEEP|" + AFTER_CONFIRM_MS,
                    "WEBCLICK|" + WINDOW + "|" + BACK,
                    "WEBWAIT|" + WINDOW + "|" + RESET + "||" + PAGE_TIMEOUT_MS,
                    "WEBCLICK|" + WINDOW + "|" + RESET,
                    "WEBVALUE|" + WINDOW + "|" + EAN_FIELD + "|^$|" + PAGE_TIMEOUT_MS,
                    "WEBVALUE|" + WINDOW + "|" + ITM8_FIELD + "|^0*$|" + PAGE_TIMEOUT_MS));
        }
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    /** Liczba z przecinkiem, jak pokazuje ją Mercalys (12 → „12”, 1.5 → „1,50”). */
    static String commaNumber(BigDecimal value, int digits) {
        return (value == null ? BigDecimal.ZERO : value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
                .replace('.', ',');
    }

    /** Mercalys przyjmuje daty jako DDMMRR. */
    static String mercalysDate(LocalDate date) {
        return "%02d%02d%02d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
    }

    /** Po wyjściu z pola Mercalys może dopisać ukośniki i pełny rok. */
    static String datePattern(LocalDate date) {
        return "^%02d/?%02d/?(20)?%02d$".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
    }

    /** ITM8 i kody bez znaków spoza cyfr i bez zer wiodących. */
    public static String key(String code) {
        return code == null ? "" : code.replaceAll("\\D", "").replaceFirst("^0+", "");
    }
}
