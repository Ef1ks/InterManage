package com.intermanage.backend.cadtop.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro ustawiające ITM8 z cadencier jako priorytetowy i aktywny na karcie artykułu (odtwarzacz od 0.64, jak każde
 * makro na komputerze sklepu). Składa je serwer: ta sama treść idzie do schowka i na komputer sklepu.
 *
 * <p>Każdy artykuł: Karta Artykułu (ar-e001) po obecnym ITM8, sprawdzenie EAN-u karty (lokalny ITM8 99… jest w innym
 * sklepie innym towarem), „Kod ITM8 :” (ar-e006), ITM8 z cadencier z Tab. Zaznacza Priorytetowy i Aktywny, o ile nie są
 * zaznaczone, i zapisuje („Czy zachować wpis?” = Tak); gdy oba już są, wraca bez zapisu, więc makro można powtórzyć.
 * ITM8, którego nie ma jeszcze w Mercalys (pusty wpis albo pytanie o utworzenie), jest pomijany z powodem w wyniku,
 * bo bez dostawcy, opakowania i ceny powstałby pusty wpis.
 *
 * <p>Karta bez kodu CN pokazuje przy wczytaniu „Kody CN, GTU i Split Payment są wymagane”: makro zamyka komunikat,
 * wpisuje w „Kod CN:” (ar-e131) kod {@value #CN}, zapisuje kartę i dopiero potem przechodzi do ITM8.
 */
public final class CadPriorityMacro {

    private static final String WINDOW = "Mercalys";
    private static final String DIALOG = "Les Mousquetaires";
    /** Tylko okna komunikatów: przycisku bez id szuka się po napisie, a w głównym oknie trwałoby to sekundy. */
    private static final String ALERT = "okno dialogowe strony";
    static final String CN = "99999980";
    private static final int PAGE_TIMEOUT_MS = 30_000;
    private static final int LOAD_TIMEOUT_MS = 15_000;
    private static final int ITM8_TIMEOUT_MS = 8_000;
    /** Komunikat „Ten kod ITM jest już przyznany” przychodzi razem z wczytaniem wpisu po Tab. */
    private static final int ASSIGNED_CHECK_MS = 700;
    private static final int AFTER_CONFIRM_MS = 1_500;
    private static final int AFTER_CHECK_MS = 500;
    /** „Sprzedaż ze stratami! Minimalna cena sprzedaży jest …”: przy zaznaczaniu albo przy zapisie, tylko czasem. */
    private static final String LOSS_ALERT = "Minimalna cena";
    /** Komunikat o kodach CN pokazuje się razem z wczytaną kartą. */
    private static final int CN_ALERT_MS = 500;
    private static final int CARD_CHECK_MS = 3_000;
    private static final int EMPTY_CARD_MS = 2_000;
    /** Po tylu ms bez karty Powrót klika się drugi raz. */
    private static final int BACK_RETRY_MS = 15_000;

    // Karta artykułu (ar-e001) i „Kod ITM8 :” (ar-e006).
    private static final String ARTICLE_TAB = "mnTp_ARTICLE";
    private static final String ARTICLE_MENU_ITEM = "mnTv_ARTICLE_ar-e001_FicheArticle";
    private static final String CARD_ITM8 = "txtZ1_CodeITM_TxtBox";
    private static final String CARD_EAN = "txtZ4_CodeEAN_TxtBox";
    private static final String CARD_NAME = "txtZ6_Libelle_TxtBox";
    private static final String ITM8_BUTTON = "lnkZ1_CodeITM_LnkAviso";
    private static final String RESET = "btnB2_RAZ_LnkBouton";
    private static final String CN_BUTTON = "Lnk_CodeCN_LnkAviso";
    // Ekran „Kod CN:” (ar-e131.aspx?source=are001&chrono=…).
    private static final String CN_SCREEN = "ar-e131";
    private static final String CN_FIELD = "txt_CodeCn_TxtBox";
    private static final String CN_BACK = "btn_Retour_LnkBouton";
    /** Ekran kodu CN odpowiada szybko (sprawdzone 25.11.2026), więc krótsze przerwy niż przy zapisie karty. */
    private static final int CN_STEP_MS = 500;
    /** Pytania po Zatwierdź na karcie: zapis i, czasem, sprzedaż ze stratami. */
    private static final String CARD_SAVE_QUESTIONS = "zachowa;stratami";
    private static final String CARD_APPROVE = "btnB6_Valider_LnkBouton";
    private static final String NEW_ITM8 = "txtZ02_Itm_TxtBox";
    private static final String NEW_ITM8_SUPPLIER = "lstZ04_Fournisseur_TxtBox";
    private static final String PRIORITY_CHECKBOX = "chkZ03_Prioritaire_CheckBox1";
    private static final String ACTIVE_CHECKBOX = "chkZ10_Actif_CheckBox1";
    private static final String APPROVE = "btnB4_Valider_LnkBouton";
    private static final String BACK = "btnB1_Retour_LnkBouton";

    /**
     * „Ten kod ITM jest już przyznany” (DialogAlert po Tab w polu ITM8, 25.11.2026): ITM8 z cadencier jest na innej
     * karcie, z innym EAN-em. Powód w wyniku zaczyna się od {@link #ASSIGNED_ELSEWHERE}, po nim raport odkłada artykuł do
     * ręcznej poprawy.
     */
    private static final String ASSIGNED_ALERT = "przyznany";
    public static final String ASSIGNED_ELSEWHERE = "ITM8 jest już na innej karcie w Mercalys";

    private static final String SKIPPED = "Pominięto ";
    private static final String END = "koniec-";
    private static final Pattern SKIPPED_LINE = Pattern.compile("^" + SKIPPED + "(\\d+): (.+)$", Pattern.MULTILINE);
    private static final Pattern STOPPED_AT = Pattern.compile("na linii (\\d+)");

    /** {@code ean}: kod karty w sklepie, {@code itm8}: jej obecny ITM8, {@code topItm8}: ten z cadencier. */
    public record Item(String ean, String itm8, String topItm8) {
    }

    private CadPriorityMacro() {
    }

    public static String build(List<Item> items) {
        List<String> out = new ArrayList<>();
        out.add("# ITM8 z cadencier, priorytet i aktywność: " + items.size());
        out.add(ReportSaveMacro.OPEN_LINE);
        out.add("WEBENSURE|" + WINDOW + "|" + ARTICLE_MENU_ITEM + "|" + ARTICLE_TAB);
        out.add("WEBCLICK|" + WINDOW + "|" + ARTICLE_MENU_ITEM);
        out.add("SLEEP|" + AFTER_CONFIRM_MS);
        out.add("WEBWAIT|" + WINDOW + "|" + RESET + "||" + PAGE_TIMEOUT_MS);
        for (int index = 0; index < items.size(); index++) {
            out.addAll(steps(items.get(index), index + 1, items.size()));
        }
        out.add("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    private static List<String> steps(Item item, int number, int count) {
        String card = key(item.itm8());
        String top = key(item.topItm8());
        String ean = key(item.ean());
        String cnCheck = "cn-" + card;
        String cnReady = "cn-jest-" + card;
        String noCn = "brak-cn-" + card;
        String change = "zmien-" + card;
        String question = "pytanie-" + card;
        String back = "powrot-" + card;
        String backAgain = "powrot-znow-" + card;
        String noCard = "brak-karty-" + card;
        String otherEan = "inny-ean-" + card;
        String noItm8 = "brak-itm8-" + card;
        String noItm8Alert = "brak-itm8-bez-karty-" + card;
        String free = "wolny-" + card;
        String creates = "nowy-" + card;
        String leave = "wyjscie-" + card;
        String menu = "menu-" + card;
        String clear = "czysc-" + card;
        List<String> out = new ArrayList<>();
        out.add("# " + number + "/" + count + ": karta " + card + ", ITM8 z cadencier " + top);
        out.add("WEBWAIT|" + WINDOW + "|" + CARD_ITM8 + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBKEY|" + WINDOW + "|" + CARD_ITM8 + "|^a" + card + "{Enter}");
        // Komunikat o kodach CN może wstrzymać wczytanie karty, więc brak nazwy rozstrzyga się dopiero po nim.
        out.add("WEBIFVALUE|" + WINDOW + "|" + CARD_NAME + "|\\S|" + cnCheck + "|" + LOAD_TIMEOUT_MS);
        out.add("LABEL|" + cnCheck);
        out.add("IFDIALOG|" + ALERT + "|Kody CN|Fermer|" + cnReady + "|" + CN_ALERT_MS);
        out.addAll(checkCard(ean, noCard, otherEan, LOAD_TIMEOUT_MS));
        out.addAll(enterCn(noCn));
        out.add("LABEL|" + cnReady);
        out.addAll(checkCard(ean, noCard, otherEan, CARD_CHECK_MS));
        out.add("WEBCLICK|" + WINDOW + "|" + ITM8_BUTTON);
        out.add("WEBWAIT|" + WINDOW + "|" + NEW_ITM8 + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBKEY|" + WINDOW + "|" + NEW_ITM8 + "|^a" + top + "{Tab}");
        // Pusty wpis ma dostawcę 0; wczytany istniejący ITM8 ma swojego.
        out.add("WEBIFVALUE|" + WINDOW + "|" + NEW_ITM8_SUPPLIER + "|^0*[1-9]\\d*$|" + noItm8 + "|" + ITM8_TIMEOUT_MS);
        out.add("IFDIALOG|" + ALERT + "|" + ASSIGNED_ALERT + "|Fermer|" + free + "|" + ASSIGNED_CHECK_MS);
        out.add(jump(leave, skippedLine(card, assignedReason(top))));
        out.add("LABEL|" + free);
        out.add("WEBVALUE|" + WINDOW + "|" + NEW_ITM8 + "|^0*" + top + "$");
        out.add("WEBIFVALUE|" + WINDOW + "|" + PRIORITY_CHECKBOX + "|^tak$|" + change + "|1000");
        out.add("WEBIFVALUE|" + WINDOW + "|" + ACTIVE_CHECKBOX + "|^nie$|" + back + "|1000");
        out.add("LABEL|" + change);
        // Priorytet, który już jest, ma pole zablokowane, więc klika się tylko odznaczone. Najpierw priorytet.
        out.addAll(tick(PRIORITY_CHECKBOX, "priorytet-" + card));
        out.addAll(tick(ACTIVE_CHECKBOX, "aktywny-" + card));
        out.add(closeLossAlert(500));
        out.add("WEBVALUE|" + WINDOW + "|" + PRIORITY_CHECKBOX + "|^tak$");
        out.add("WEBVALUE|" + WINDOW + "|" + ACTIVE_CHECKBOX + "|^tak$");
        out.add("WEBVALUE|" + WINDOW + "|" + NEW_ITM8 + "|^0*" + top + "$");
        out.add("WEBCLICK|" + WINDOW + "|" + APPROVE);
        // Komunikat o stracie przed pytaniem; gdy po nim pytania nie ma, zostaje Powrót.
        out.add("IFDIALOG|" + ALERT + "|" + LOSS_ALERT + "|Fermer|" + question + "|1000");
        out.add("WEBIFSEEN|" + DIALOG + "|Oui|" + back + "|5000");
        out.add("LABEL|" + question);
        out.add("WEBWAIT|" + DIALOG + "|Oui|zachowa;Utworzy|" + PAGE_TIMEOUT_MS);
        out.add("IFDIALOG|" + DIALOG + "|zachowa|Oui|" + creates + "|5000");
        out.add(closeLossAlert(2_000));
        // Zapis potrafi chwilę trwać, a Powrót kliknięty w trakcie ginie (25.11.2026 makro stało na karcie
        // 30 s): czekanie na przycisk po zapisie, a gdy karta nie wróci, drugi klik.
        out.add("LABEL|" + back);
        out.add("SLEEP|" + AFTER_CHECK_MS);
        out.add("WEBWAIT|" + WINDOW + "|" + BACK + "||" + PAGE_TIMEOUT_MS);
        out.add("WEBCLICK|" + WINDOW + "|" + BACK);
        out.add("WEBIFSEEN|" + WINDOW + "|" + RESET + "|" + backAgain + "|" + BACK_RETRY_MS);
        out.add(jump(clear, ""));
        out.add("LABEL|" + backAgain);
        out.add("WEBCLICK|" + WINDOW + "|" + BACK);
        out.add("WEBWAIT|" + WINDOW + "|" + RESET + "||" + PAGE_TIMEOUT_MS);
        out.add(jump(clear, ""));

        out.add("LABEL|" + noCard);
        out.add(closeAlert());
        out.add(jump(clear, skippedLine(card, "karta o tym ITM8 się nie otworzyła")));
        out.add("LABEL|" + otherEan);
        out.add(jump(clear, skippedLine(card, "na karcie jest inny EAN niż w kartotece")));
        out.add("LABEL|" + noCn);
        out.add("WEBDIALOG|" + ALERT + "|CN;GTU;Split;stawk|Fermer|1500");
        out.add(jump(leave, skippedLine(card, "nie udało się wpisać kodu CN na karcie")));
        out.add("LABEL|" + creates);
        out.add("WEBDIALOG|" + DIALOG + "|Utworzy|Non|3000");
        out.add(jump(leave, skippedLine(card, "ITM8 " + top + " nie ma jeszcze na tej karcie w Mercalys")));
        out.add("LABEL|" + noItm8);
        // Komunikat o ITM8 na innej karcie wstrzymuje wczytanie wpisu, więc częściej kończy się tutaj.
        out.add("IFDIALOG|" + ALERT + "|" + ASSIGNED_ALERT + "|Fermer|" + noItm8Alert + "|" + ASSIGNED_CHECK_MS);
        out.add(jump(leave, skippedLine(card, assignedReason(top))));
        out.add("LABEL|" + noItm8Alert);
        out.add(closeAlert());
        out.add(jump(leave, skippedLine(card, "ITM8 " + top + " nie ma jeszcze w Mercalys")));

        // Wyjście bez zapisu: Mercalys może zapytać o zachowanie wpisu albo zostać na stronie.
        out.add("LABEL|" + leave);
        out.add("WEBCLICK|" + WINDOW + "|" + BACK);
        out.add("WEBDIALOG|" + DIALOG + "|zachowa;zapis|Non|1500");
        out.add("WEBIFSEEN|" + WINDOW + "|" + RESET + "|" + menu + "|10000");
        out.add(jump(clear, ""));
        out.add("LABEL|" + menu);
        out.add("WEBENSURE|" + WINDOW + "|" + ARTICLE_MENU_ITEM + "|" + ARTICLE_TAB);
        out.add("WEBCLICK|" + WINDOW + "|" + ARTICLE_MENU_ITEM);
        out.add("WEBWAIT|" + WINDOW + "|" + RESET + "||" + PAGE_TIMEOUT_MS);

        out.add("LABEL|" + clear);
        out.addAll(clearCard("pusta-" + card));
        out.add("LABEL|" + END + card);
        return out;
    }

    private static String closeLossAlert(int timeoutMs) {
        return "WEBDIALOG|" + ALERT + "|" + LOSS_ALERT + "|Fermer|" + timeoutMs;
    }

    /** Zaznacza odznaczone pole; zaznaczone (także zablokowane) zostawia. */
    private static List<String> tick(String checkbox, String ticked) {
        return List.of(
                "WEBIFVALUE|" + WINDOW + "|" + checkbox + "|^nie$|" + ticked + "|1000",
                "WEBCLICK|" + WINDOW + "|" + checkbox,
                "SLEEP|" + AFTER_CHECK_MS,
                "LABEL|" + ticked);
    }

    /** Wczytana karta i jej EAN (lokalny ITM8 99… jest w innym sklepie innym towarem). */
    private static List<String> checkCard(String ean, String noCard, String otherEan, int timeoutMs) {
        List<String> out = new ArrayList<>();
        out.add("WEBIFVALUE|" + WINDOW + "|" + CARD_NAME + "|\\S|" + noCard + "|" + timeoutMs);
        if (!ean.isEmpty()) {
            out.add("WEBIFVALUE|" + WINDOW + "|" + CARD_EAN + "|^0*" + ean + "$|" + otherEan + "|3000");
        }
        return out;
    }

    /**
     * „Kod CN:” z karty (ar-e131, skan 25.11.2026): kod w polu, Enter, Enter, Tak, Powrót, potem zapis karty. Kod
     * sprawdza się na ekranie CN przed zapisem, bo karta pola z kodem nie ma.
     */
    private static List<String> enterCn(String noCn) {
        return List.of(
                "WEBCLICK|" + WINDOW + "|" + CN_BUTTON,
                "WEBPAGE|" + WINDOW + "|" + CN_SCREEN + "|" + CN_FIELD + "|" + PAGE_TIMEOUT_MS,
                "WEBKEY|" + WINDOW + "|" + CN_FIELD + "|^a" + CN,
                "WEBIFVALUE|" + WINDOW + "|" + CN_FIELD + "|^\\s*" + CN + "\\s*$|" + noCn + "|5000",
                "WEBKEY|" + WINDOW + "|" + CN_FIELD + "|{Enter}",
                "SLEEP|" + CN_STEP_MS,
                "WEBDIALOG|" + ALERT + "|stawk;kodu CN|Fermer|500",
                "KEY|{Enter}",
                "WEBDIALOG|" + DIALOG + "|zachowa;Utworzy|Oui|10000",
                "SLEEP|" + CN_STEP_MS,
                "WEBCLICK|" + WINDOW + "|" + CN_BACK,
                "WEBDIALOG|" + DIALOG + "|zachowa;zapis|Oui|1500",
                "WEBIFSEEN|" + WINDOW + "|" + RESET + "|" + noCn + "|" + PAGE_TIMEOUT_MS,
                "WEBCLICK|" + WINDOW + "|" + CARD_APPROVE,
                // Rzadko przed albo po „Czy zachować wpis?” przychodzi „Sprzedaż ze stratami! … Czy potwierdzasz”
                // (BoiteConfirmation, Tak / Nie): oba pytania zatwierdza się Tak, w dowolnej kolejności.
                "IFDIALOG|" + DIALOG + "|" + CARD_SAVE_QUESTIONS + "|Oui|" + noCn + "|" + PAGE_TIMEOUT_MS,
                "WEBDIALOG|" + DIALOG + "|" + CARD_SAVE_QUESTIONS + "|Oui|1500",
                "SLEEP|" + AFTER_CONFIRM_MS,
                "WEBIFVALUE|" + WINDOW + "|" + CARD_NAME + "|\\S|" + noCn + "|" + LOAD_TIMEOUT_MS);
    }

    /**
     * ZER. po skończonym artykule, przed następnym: bez tego jego wartości wyglądałyby na wczytane. Nie na początku:
     * na karcie bez artykułu (świeżo otwartej albo z ITM8, którego nie ma) ZER. jest wyłączony.
     */
    private static List<String> clearCard(String empty) {
        return List.of(
                "WEBIFVALUE|" + WINDOW + "|" + CARD_NAME + "|\\S|" + empty + "|" + EMPTY_CARD_MS,
                "WEBCLICK|" + WINDOW + "|" + RESET,
                "WEBVALUE|" + WINDOW + "|" + CARD_NAME + "|^$|" + PAGE_TIMEOUT_MS,
                "WEBVALUE|" + WINDOW + "|" + CARD_ITM8 + "|^0*$|" + PAGE_TIMEOUT_MS,
                "LABEL|" + empty);
    }

    /**
     * Bezwarunkowy przeskok z opcjonalnym wpisem do podsumowania: okna o takim numerze nigdy nie ma, więc WEBIFSEEN
     * przeskakuje od razu, bez przeglądania stron Mercalys.
     */
    private static String jump(String label, String note) {
        return "WEBIFSEEN|ahk_id 1|brak|" + label + "|0" + (note.isEmpty() ? "" : "|" + note);
    }

    private static String closeAlert() {
        return "WEBDIALOG|" + DIALOG + "|istniej;nieznany;nie znaleziono|Fermer|1500";
    }

    private static String assignedReason(String top) {
        return ASSIGNED_ELSEWHERE + " (" + top + "), popraw ręcznie";
    }

    /** Powód pominięcia z makra: ITM8 z cadencier jest na innej karcie. */
    public static boolean assignedElsewhere(String failure) {
        return failure != null && failure.startsWith(ASSIGNED_ELSEWHERE);
    }

    private static String skippedLine(String card, String reason) {
        return SKIPPED + card + ": " + reason;
    }

    /** Pominięte artykuły z wyniku makra: ITM8 karty (bez zer wiodących) i powód. */
    public static Map<String, String> skipped(String message) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = SKIPPED_LINE.matcher(message == null ? "" : message.replace("\r", ""));
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2).strip());
        }
        return result;
    }

    /**
     * Karty skończone przed błędem, po numerze linii z opisu błędu. Odtwarzacz liczy też pierwszą linię z
     * identyfikatorem wyniku, którą dokłada agent.
     */
    public static Set<String> completed(String macro, String message) {
        Matcher matcher = STOPPED_AT.matcher(message == null ? "" : message);
        Set<String> result = new LinkedHashSet<>();
        if (matcher.find()) {
            String[] lines = macro.split("\r\n");
            int stoppedAt = Integer.parseInt(matcher.group(1)) - 2; // indeks od 0, bez linii agenta
            for (int index = 0; index < Math.min(lines.length, stoppedAt); index++) {
                if (lines[index].startsWith("LABEL|" + END)) {
                    result.add(lines[index].substring(("LABEL|" + END).length()));
                }
            }
        }
        return result;
    }

    /** Kod bez znaków spoza cyfr i bez zer wiodących. */
    public static String key(String code) {
        return code == null ? "" : code.replaceAll("\\D", "").replaceFirst("^0+", "");
    }
}
