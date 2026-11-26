package com.intermanage.backend.promotion.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Wprowadzanie promocji składane przez serwer (agent sklepu wykonuje tylko makra z serwera): te same polecenia co
 * {@code buildPromoEntryMacro} w {@code mercalys-macro.ts}; zmiana ekranów Mercalys = zmiana w obu miejscach.
 *
 * <p>Taryfa → Wprowadzanie promocji (ar-e072), kod i nazwa operacji (istniejąca operacja z tym kodem jest usuwana), typ 1,
 * początek (gdy po dziś), koniec = najpóźniejszy koniec artykułów, Zatwierdź, Tak, Wpisz (ar-e074); dla każdego artykułu
 * EAN, kod stawki VAT, gdy Mercalys go wyzeruje (okno o kodzie CN), koniec sprzedaży, cena z Enterem, Tak przy sprzedaży
 * ze stratą (tylko czasem) i Tak przy „Utworzyć zapis ?”. Na końcu Powrót i Mercalys na pasek zadań. Odtwarzacz od 0.50.
 */
public final class PromoEntryMacro {

    private static final String WINDOW = "Mercalys";
    private static final String PROGRAM_WINDOW = ReportSaveMacro.PROGRAM_WINDOW;
    private static final String DIALOG = "Les Mousquetaires";
    private static final int PAGE_TIMEOUT_MS = 30000;
    private static final int LOSS_DIALOG_TIMEOUT_MS = 3000;

    private static final String TARIFF_TAB = "mnTp_TARIF";
    private static final String MENU_ITEM = "mnTv_TARIF_ar-e072_SaisieDesPromos";
    private static final String CODE_FIELD = "lstZ1_CodeOp_TxtBox";
    private static final String NAME_FIELD = "txtZ2_LibelleOp_TxtBox";
    private static final String TYPE_FIELD = "lstZ3_TypeOp_TxtBox";
    private static final String TYPE = "1";
    private static final String START_FIELD = "txdZ6_DebutOp_TxtBox";
    private static final String END_FIELD = "txdZ7_FinOp_TxtBox";
    private static final String APPROVE_BUTTON = "btnB6_Valider_LnkBouton";
    private static final String ENTER_BUTTON = "btnB2_Saisir_LnkBouton";
    private static final String ARTICLE_FIELD = "txtZ8_CodeArticle_TxtBox";
    private static final String SALE_END_FIELD = "txdZ12_FinVentePDV_TxtBox";
    private static final String PRICE_FIELD = "txpZ25_PrixPromoPDV_TxtBox";
    private static final String CREATE_TEXT = "Utworzy";
    private static final String DELETE_BUTTON = "btnB3_Supprimer_LnkBouton";
    private static final String DELETE_TEXT = "usun";
    private static final String FREE_CODE_LABEL = "KOD_WOLNY";
    private static final String LOSS_TEXT = "ze strat";
    private static final String BACK_BUTTON = "btnB1_Retour_LnkBouton";
    /**
     * Komunikaty o stawce VAT i kodzie CN po EAN-ie (tylko czasem, sam „Fermer” = Zamknij, okno „:: MERCALYS - Les
     * Mousquetaires ::”): „Wprowadzona stawka podatkowa nie jest dozwolona dla wprowadzonego kodu CN”, „Przed
     * kontynuacją popraw kartę artykułu. Aby zobaczyć dozwoloną stawkę VAT wprowadź kod CN” (skan 26.11.2026), „Kody CN,
     * GTU i Split Payment są wymagane”.
     */
    private static final String CN_TEXT = "kod CN;kodu CN;Kody CN;dozwolon;stawk;GTU";
    private static final String CN_CLOSE_BUTTON = "Fermer";
    private static final String VAT_FIELD = "lstZ22_TVA_TxtBox";

    private static final int AFTER_EAN = 800;
    private static final int AFTER_SAVE = 1500;
    private static final int EXISTING_CHECK = 3000;
    private static final int BEFORE_BACK = 500;
    /** Okno o kodzie CN czeka tylko przy pustym albo zerowym VAT, więc może dłużej. */
    private static final int CN_DIALOG = 1000;
    /** Tyle makro czeka po EAN-ie na kod stawki w polu VAT; zwykle jest od razu. */
    private static final int VAT_LOAD = 2000;

    /** Artykuł promocji; {@code vatCode}: kod stawki w Mercalys (1 = 23%, 2 = 8%, 6 = 5%), pusty = nieznany. */
    public record Item(String ean, BigDecimal price, LocalDate dateTo, String vatCode) {
    }

    private PromoEntryMacro() {
    }

    /** Kod operacji jak kod taryfy pliku .PRO: cztery losowe cyfry i numer kafelka 01 (Ustaw promocje). */
    public static String randomCode() {
        return ThreadLocalRandom.current().nextInt(1000, 10000) + "01";
    }

    /** Nazwa operacji jak nazwa pliku .PRO: dzień początku i najpóźniejszego końca ({@code 29-1}). */
    public static String name(LocalDate start, LocalDate end) {
        return start.getDayOfMonth() + "-" + end.getDayOfMonth();
    }

    /** Kod stawki VAT w Mercalys z procentu; inny procent = brak. */
    public static String vatCode(BigDecimal percent) {
        if (percent == null) {
            return null;
        }
        return switch (percent.stripTrailingZeros().toPlainString()) {
            case "23" -> "1";
            case "8" -> "2";
            case "5" -> "6";
            default -> null;
        };
    }

    public static LocalDate end(List<Item> items) {
        return items.stream().map(Item::dateTo).max(LocalDate::compareTo).orElseThrow();
    }

    /** {@code start} pusty = zostaje dzisiejszy podpowiedziany przez Mercalys. */
    public static String build(String code, String name, LocalDate start, List<Item> items) {
        LocalDate end = end(items);
        List<String> out = new ArrayList<>();
        out.add("# Wprowadzanie promocji " + code + ": " + items.size() + " art." + (start != null ? ", od " + start : ""));
        out.add(ReportSaveMacro.OPEN_LINE);
        header(out, code, name, start, end);
        for (int index = 0; index < items.size(); index++) {
            article(out, items.get(index), index, items.size());
        }
        out.add("WEBWAIT|" + WINDOW + "|" + ARTICLE_FIELD + "||" + PAGE_TIMEOUT_MS);
        out.add("SLEEP|" + BEFORE_BACK);
        out.add("WEBCLICK|" + WINDOW + "|" + BACK_BUTTON);
        out.add("WEBWAIT|" + WINDOW + "|" + CODE_FIELD + "||" + PAGE_TIMEOUT_MS);
        out.add("WINMIN|" + PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    private static void header(List<String> out, String code, String name, LocalDate start, LocalDate end) {
        out.add("WEBENSURE|" + WINDOW + "|" + MENU_ITEM + "|" + TARIFF_TAB);
        out.add("WEBCLICK|" + WINDOW + "|" + MENU_ITEM);
        out.add("WEBWAIT|" + WINDOW + "|" + CODE_FIELD + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBKEY|" + WINDOW + "|" + CODE_FIELD + "|^a" + code + "{Tab}");
        // Operacja z tym kodem już jest: Usuń, Tak, kod od nowa.
        out.add("WEBIFVALUE|" + WINDOW + "|" + NAME_FIELD + "|\\S|" + FREE_CODE_LABEL + "|" + EXISTING_CHECK);
        out.add("WEBCLICK|" + WINDOW + "|" + DELETE_BUTTON);
        out.add("WEBWAIT|" + DIALOG + "|Oui|" + DELETE_TEXT + "|" + PAGE_TIMEOUT_MS);
        out.add("WEBCLICK|" + DIALOG + "|Oui");
        out.add("SLEEP|" + AFTER_SAVE);
        out.add("WEBWAIT|" + WINDOW + "|" + CODE_FIELD + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBKEY|" + WINDOW + "|" + CODE_FIELD + "|^a" + code + "{Tab}");
        out.add("WEBVALUE|" + WINDOW + "|" + NAME_FIELD + "|^$|" + EXISTING_CHECK);
        out.add("LABEL|" + FREE_CODE_LABEL);
        out.add("WEBKEY|" + WINDOW + "|" + NAME_FIELD + "|^a" + name + "{Tab}");
        out.add("WEBKEY|" + WINDOW + "|" + TYPE_FIELD + "|^a" + TYPE + "{Tab}");
        if (start != null) {
            out.add("WEBKEY|" + WINDOW + "|" + START_FIELD + "|^a" + mercalysDate(start) + "{Tab}");
        }
        out.add("WEBKEY|" + WINDOW + "|" + END_FIELD + "|^a" + mercalysDate(end) + "{Tab}");
        out.add("WEBVALUE|" + WINDOW + "|" + CODE_FIELD + "|^" + code + "$");
        out.add("WEBVALUE|" + WINDOW + "|" + TYPE_FIELD + "|^" + TYPE + "$");
        if (start != null) {
            out.add("WEBVALUE|" + WINDOW + "|" + START_FIELD + "|" + datePattern(start));
        }
        out.add("WEBVALUE|" + WINDOW + "|" + END_FIELD + "|" + datePattern(end));
        out.add("WEBCLICK|" + WINDOW + "|" + APPROVE_BUTTON);
        out.add("WEBWAIT|" + DIALOG + "|Oui|" + CREATE_TEXT + "|" + PAGE_TIMEOUT_MS);
        out.add("WEBCLICK|" + DIALOG + "|Oui");
        out.add("SLEEP|" + AFTER_SAVE);
        out.add("WEBWAIT|" + WINDOW + "|" + ENTER_BUTTON + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBCLICK|" + WINDOW + "|" + ENTER_BUTTON);
    }

    private static void article(List<String> out, Item item, int index, int count) {
        String price = item.price().setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        String vatLabel = "VAT_OK_" + (index + 1);
        String fixLabel = "VAT_FIX_" + (index + 1);
        out.add("# Artykuł " + (index + 1) + "/" + count + ", EAN " + item.ean() + ": " + price + " do " + item.dateTo());
        out.add("WEBWAIT|" + WINDOW + "|" + ARTICLE_FIELD + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBKEY|" + WINDOW + "|" + ARTICLE_FIELD + "|^a" + item.ean() + "{Enter}");
        out.add("SLEEP|" + AFTER_EAN);
        // Wczytany artykuł ma kod stawki w polu VAT: wtedy od razu dalej, bez czekania na okno, które przychodzi rzadko.
        // Pole puste albo z 0 = komunikat o kodzie CN: Fermer i kod stawki artykułu; bez znanej stawki makro staje na
        // sprawdzeniu pola, zamiast zapisać promocję z VAT 0.
        out.add("WEBIFVALUE|" + WINDOW + "|" + VAT_FIELD + "|^[1-9]$|" + fixLabel + "|" + VAT_LOAD);
        out.add(jump(vatLabel));
        out.add("LABEL|" + fixLabel);
        out.add("WEBDIALOG|" + DIALOG + "|" + CN_TEXT + "|" + CN_CLOSE_BUTTON + "|" + CN_DIALOG);
        out.add(item.vatCode() != null ? "WEBKEY|" + WINDOW + "|" + VAT_FIELD + "|^a" + item.vatCode() + "{Tab}"
                : "# Brak stawki VAT artykułu w kartotece");
        out.add("WEBVALUE|" + WINDOW + "|" + VAT_FIELD + "|^[1-9]$");
        out.add("LABEL|" + vatLabel);
        // Komunikat przy stawce w polu („VAT … nie jest już dozwolony”): sprawdzenie bez czekania.
        out.add("WEBDIALOG|" + DIALOG + "|" + CN_TEXT + "|" + CN_CLOSE_BUTTON + "|0");
        out.add("WEBKEY|" + WINDOW + "|" + SALE_END_FIELD + "|^a" + mercalysDate(item.dateTo()) + "{Tab}");
        out.add("WEBVALUE|" + WINDOW + "|" + SALE_END_FIELD + "|" + datePattern(item.dateTo()));
        out.add("WEBKEY|" + WINDOW + "|" + PRICE_FIELD + "|^a" + price + "{Enter}");
        out.add("WEBDIALOG|" + DIALOG + "|" + LOSS_TEXT + "|Oui|" + LOSS_DIALOG_TIMEOUT_MS);
        out.add("WEBWAIT|" + DIALOG + "|Oui|" + CREATE_TEXT + "|" + PAGE_TIMEOUT_MS);
        out.add("WEBCLICK|" + DIALOG + "|Oui");
        out.add("SLEEP|" + AFTER_SAVE);
    }

    /** Skok bez warunku: pola nie ma, więc WEBIFVALUE od razu przeskakuje (działa w każdym odtwarzaczu z LABEL). */
    private static String jump(String label) {
        return "WEBIFVALUE|" + WINDOW + "|brak-pola|^$|" + label + "|0";
    }

    static String mercalysDate(LocalDate date) {
        return "%02d%02d%02d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
    }

    static String datePattern(LocalDate date) {
        return "^%02d/?%02d/?(20)?%02d$".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);
    }
}
