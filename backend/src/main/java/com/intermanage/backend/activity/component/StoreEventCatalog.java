package com.intermanage.backend.activity.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Opis zmiany po polsku ze ścieżki API, bez dopisywania czegokolwiek w kontrolerach: moduł z pierwszego członu po
 * sklepie ({@code /api/stores/{id}/documents/...} = Dokumenty), czynność z ostatniego członu ({@code restore} =
 * przywrócenie) albo z metody, a przedmiot z ostatniego członu, który nie jest identyfikatorem. Nieznany człon zostaje
 * czytelnym tekstem (myślniki na spacje), więc nowy endpoint od razu ma wpis, tylko mniej ładny.
 */
public final class StoreEventCatalog {

    private static final Pattern ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+|\\d{4}-\\d{2}(-\\d{2})?");

    /** Moduł (klucz w bazie) → nazwa w dzienniku. Kolejność = kolejność na liście filtra. */
    private static final Map<String, String> MODULES = new LinkedHashMap<>();
    /** Pierwszy człon ścieżki sklepu → moduł. */
    private static final Map<String, String> STORE_SEGMENTS = Map.ofEntries(
            Map.entry("documents", "documents"), Map.entry("incoming-transfers", "documents"),
            Map.entry("cash", "cash"),
            Map.entry("imports", "imports"), Map.entry("import-guides", "imports"),
            Map.entry("data-entry", "data-entry"), Map.entry("price-markdowns", "inventory"),
            Map.entry("products", "inventory"), Map.entry("stocktakes", "inventory"),
            Map.entry("deli-order", "deli"), Map.entry("price-calculations", "deli"), Map.entry("production", "deli"),
            Map.entry("ksef", "ksef"), Map.entry("suppliers", "suppliers"),
            Map.entry("meat-deliveries", "purchases"), Map.entry("bakery-checks", "purchases"),
            Map.entry("base-deliveries", "purchases"), Map.entry("mea-codes", "purchases"),
            Map.entry("mea", "promotions"), Map.entry("leaflets", "promotions"),
            Map.entry("promotion-setup", "promotions"),
            Map.entry("reports", "reports"), Map.entry("article-links", "reports"),
            Map.entry("ai-usage", "reports"),
            Map.entry("partners", "store-settings"), Map.entry("sidebar-order", "store-settings"),
            Map.entry("file-locations", "store-settings"), Map.entry("proto-settings", "store-settings"),
            Map.entry("nomenclature-margins", "store-settings"), Map.entry("price-rounding", "store-settings"),
            Map.entry("supplier-sale-prices", "store-settings"),
            Map.entry("file-queue", "files"), Map.entry("tasks", "tasks"));

    static {
        MODULES.put("documents", "Dokumenty");
        MODULES.put("purchases", "Zakupy");
        MODULES.put("data-entry", "Wprowadzanie danych");
        MODULES.put("promotions", "Promocje i MEA");
        MODULES.put("reports", "Raporty");
        MODULES.put("deli", "Garmaż");
        MODULES.put("inventory", "Magazyn i produkty");
        MODULES.put("imports", "Import danych");
        MODULES.put("staff", "Pracownicy");
        MODULES.put("schedule", "Grafik");
        MODULES.put("time", "Czas pracy");
        MODULES.put("records", "Kadry");
        MODULES.put("cash", "Raport kasowy");
        MODULES.put("ksef", "Faktury KSeF");
        MODULES.put("suppliers", "Dostawcy");
        MODULES.put("store-settings", "Ustawienia sklepu");
        MODULES.put("files", "Kolejka plików");
        MODULES.put("tasks", "Zadania");
        MODULES.put("company", "Ustawienia firmy");
        MODULES.put("system", "System");
        MODULES.put("other", "Inne");
    }

    /** Człony, które same są czynnością; przedmiotem jest wtedy człon przed nimi. */
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("restore", "Przywrócenie"), Map.entry("undelete", "Przywrócenie"),
            Map.entry("undo", "Cofnięcie"), Map.entry("cancel", "Anulowanie"),
            Map.entry("approve", "Zatwierdzenie"), Map.entry("reject", "Odrzucenie"),
            Map.entry("accept", "Przyjęcie"), Map.entry("publish", "Publikacja"),
            Map.entry("fulfill", "Realizacja"), Map.entry("merge", "Połączenie"), Map.entry("split", "Rozdzielenie"),
            Map.entry("transfer", "Przeniesienie"), Map.entry("sync", "Pobranie z serwera"),
            Map.entry("import", "Import"), Map.entry("saved", "Zapis pliku"), Map.entry("resave", "Ponowny zapis"),
            Map.entry("bulk", "Zmiana zbiorcza"), Map.entry("receive", "Wpisanie przyjętych ilości"),
            Map.entry("entered", "Oznaczenie wprowadzenia"), Map.entry("generated", "Wygenerowanie"),
            Map.entry("delete", "Usunięcie"), Map.entry("remove", "Usunięcie"), Map.entry("auto", "Automatyczne"),
            Map.entry("paste", "Wklejenie"), Map.entry("position", "Zmiana kolejności"),
            Map.entry("loaded", "Oznaczenie wgrania"), Map.entry("invite", "Zaproszenie"),
            Map.entry("link", "Podłączenie"), Map.entry("share", "Udostępnienie"),
            Map.entry("done", "Oznaczenie wykonania"), Map.entry("undone", "Cofnięcie wykonania"),
            Map.entry("return", "Odesłanie do poprawy"));

    /** Człony, które nic nie mówią o przedmiocie (sposób wysyłki, wersja API). */
    private static final List<String> NOISE = List.of("uploads", "jobs", "file", "export", "settings");

    /** Przedmiot zmiany: człon ścieżki → rzeczownik. */
    private static final Map<String, String> OBJECTS = Map.ofEntries(
            Map.entry("documents", "dokument"), Map.entry("corrections", "korekta przyjęcia"),
            Map.entry("tasks", "zadanie"), Map.entry("attachments", "zrzut przy zadaniu"),
            Map.entry("ksef-invoices", "faktura KSeF przy dokumencie"), Map.entry("manual-invoices", "faktura spoza KSeF"),
            Map.entry("gratis", "gratis"), Map.entry("error", "oznaczenie błędu przyjęcia"),
            Map.entry("supplier-document-number", "numer dokumentu dostawcy"),
            Map.entry("amount-confirmation", "potwierdzenie kwot"), Map.entry("transfer-target", "sklep docelowy MM"),
            Map.entry("receipt", "potwierdzenie MM"), Map.entry("report-closings", "zamknięcie raportu przyjęć"),
            Map.entry("auto-ksef-invoices", "automatyczne przypisanie faktur"),
            Map.entry("entries", "grafik"), Map.entry("employees", "pracownik"), Map.entry("account", "konto pracownika"),
            Map.entry("leaves", "urlop"), Map.entry("contracts", "umowa"), Map.entry("changes", "aneks"),
            Map.entry("decisions", "decyzja w karcie czasu pracy"), Map.entry("punches", "odbicie karty"),
            Map.entry("requests", "wniosek pracownika"), Map.entry("time-off", "czas wolny"),
            Map.entry("open-holidays", "otwarte święto"), Map.entry("layout", "układ grafiku"),
            Map.entry("rates", "stawka godzinowa"), Map.entry("departments", "dział"), Map.entry("positions", "stanowisko"),
            Map.entry("orders", "zamówienie"), Map.entry("items", "pozycje zamówienia"),
            Map.entry("delivery-date", "data dostawy"), Map.entry("categories", "kategoria"),
            Map.entry("sections", "dział formularza"), Map.entry("invitations", "zaproszenie"),
            Map.entry("products", "artykuł"), Map.entry("reviews", "status wyjaśnienia z dostawcą"),
            Map.entry("suppliers", "dostawca"), Map.entry("contacts", "kontakt"), Map.entry("links", "przypięcie artykułu"),
            Map.entry("price-markdowns", "przecena"), Map.entry("labels-file", "plik cenówek"),
            Map.entry("cards", "karta produkcji"), Map.entry("xml", "plik XML faktury"),
            Map.entry("product-file", "plik nowych artykułów"), Map.entry("price-control", "zmiana cen"),
            Map.entry("protocols", "protokół"), Map.entry("labels", "etykiety"), Map.entry("order", "zamówienie"),
            Map.entry("suggestions", "propozycje AI"), Map.entry("marks", "oznaczenie faktury"),
            Map.entry("stamp", "pieczątka"), Map.entry("opening-balance", "stan początkowy kasy"),
            Map.entry("closings", "zamknięcie raportu"), Map.entry("partners", "sklep zaprzyjaźniony"),
            Map.entry("sales", "raport sprzedaży"), Map.entry("promotions", "lista promocji"),
            Map.entry("product-codes", "multikody"), Map.entry("nomenclatures", "nomenklatura"),
            Map.entry("cadencier", "lista cadencier"), Map.entry("incidents", "incydenty bazy"),
            Map.entry("xml-temporary", "zapis tymczasowy"), Map.entry("xml-history", "plik XML"),
            Map.entry("product-drafts", "szkic artykułu"), Map.entry("verifications", "weryfikacja marży"),
            Map.entry("excluded", "pominięty artykuł"), Map.entry("excluded-suppliers", "pomijani dostawcy"),
            Map.entry("control-groups", "dostawca do kontroli strat"), Map.entry("assignments", "przypisanie kategorii"),
            Map.entry("region-budgets", "budżet rejonów"), Map.entry("profit-targets", "cel zysku"),
            Map.entry("base-deliveries", "dostawa z bazy"), Map.entry("confirmations", "potwierdzenie kodów MEA"),
            Map.entry("athos-order", "zamówienie MEA do ATHOS"), Map.entry("athos-queue", "kolejka ATHOS"),
            Map.entry("athos-orders", "zamówienie MEA do ATHOS"), Map.entry("leaflets", "gazetka"),
            Map.entry("stocktakes", "inwentura"), Map.entry("meat-deliveries", "dokument WZ mięsa"),
            Map.entry("scan", "skan"), Map.entry("wz", "WZ piekarni"), Map.entry("bakery-checks", "weryfikacja piekarni"),
            Map.entry("price-calculations", "kalkulacja ceny"), Map.entry("refresh-prices", "ceny kalkulacji"),
            Map.entry("connection", "połączenie z KSeF"), Map.entry("invoices", "faktura"),
            Map.entry("file-queue", "plik w kolejce"), Map.entry("sidebar-order", "kolejność menu"),
            Map.entry("file-locations", "ścieżki plików"), Map.entry("proto-settings", "ustawienia protokołów"),
            Map.entry("nomenclature-margins", "marża wymagana"), Map.entry("price-rounding", "zaokrąglanie cen"),
            Map.entry("supplier-sale-prices", "stała cena dostawcy"), Map.entry("users", "użytkownik"),
            Map.entry("roles", "rola"), Map.entry("stores", "sklep"), Map.entry("backup", "kopia zapasowa"),
            Map.entry("downloads", "plik do pobrania"), Map.entry("shortcuts", "skrót do folderu"),
            Map.entry("readers", "czytnik kart"), Map.entry("nfc-cards", "karta NFC"),
            Map.entry("employer", "dane pracodawcy"), Map.entry("rules", "zasady odbić"),
            Map.entry("offers", "propozycja zasad odbić"), Map.entry("article-links", "połączenie artykułów sklepów"),
            Map.entry("mercalys-creations", "zakładanie dostawcy w Mercalys"),
            Map.entry("promo-prices", "ceny artykułów w promocji"), Map.entry("closings-invoices", "zamknięcie faktur"));

    private static final Map<String, String> METHOD_ACTIONS = Map.of(
            "POST", "Dodanie", "PUT", "Zmiana", "PATCH", "Zmiana", "DELETE", "Usunięcie");

    private StoreEventCatalog() {
    }

    /** Opis zmiany w sklepie (ścieżka po {@code /api/stores/{id}/}). */
    public static Description describeStore(String method, String tail) {
        List<String> segments = segments(tail);
        String first = segments.isEmpty() ? "" : segments.getFirst();
        String module = STORE_SEGMENTS.getOrDefault(first, "other");
        if ("staff".equals(first)) {
            String second = segments.size() > 1 ? segments.get(1) : "";
            module = switch (second) {
                case "schedule" -> "schedule";
                case "time" -> "time";
                case "records" -> "records";
                default -> "staff";
            };
        }
        return new Description(module, summary(method, segments));
    }

    /** Opis zmiany poza sklepem ({@code /api/settings/...} i inne ścieżki bez sklepu). */
    public static Description describeCompany(String method, String path) {
        List<String> segments = segments(path.replaceFirst("^/api/", ""));
        if (!segments.isEmpty() && "settings".equals(segments.getFirst())) {
            segments = segments.subList(1, segments.size());
        }
        return new Description("company", summary(method, segments));
    }

    public static String label(String module) {
        return module == null ? MODULES.get("other") : MODULES.getOrDefault(module, MODULES.get("other"));
    }

    /** Moduły do filtra dziennika, w kolejności listy. */
    public static Map<String, String> modules() {
        return MODULES;
    }

    private static String summary(String method, List<String> segments) {
        List<String> words = new ArrayList<>();
        for (String segment : segments) {
            if (!ID.matcher(segment).matches() && !NOISE.contains(segment)) {
                words.add(segment);
            }
        }
        if (words.isEmpty()) {
            return METHOD_ACTIONS.getOrDefault(method, method);
        }
        String last = words.getLast();
        String action = ACTIONS.get(last);
        String object;
        if (action != null) {
            object = words.size() > 1 ? words.get(words.size() - 2) : null;
            // „DELETE …/restore” się nie zdarza, ale „DELETE …/loaded” cofa oznaczenie.
            if ("DELETE".equals(method)) {
                action = "Cofnięcie: " + action.toLowerCase();
            }
        } else {
            action = METHOD_ACTIONS.getOrDefault(method, method);
            object = last;
        }
        return object == null ? action : action + ": " + noun(object);
    }

    private static String noun(String segment) {
        return OBJECTS.getOrDefault(segment, segment.replace('-', ' '));
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    public record Description(String module, String summary) {
    }
}
