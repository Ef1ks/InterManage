package com.intermanage.backend.auth.component;

import static com.intermanage.backend.auth.entity.PermissionCategory.*;
import static com.intermanage.backend.auth.entity.PermissionLevel.EDIT;
import static com.intermanage.backend.auth.entity.PermissionLevel.VIEW;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;

/**
 * Rangi, które dostaje każdy sklep: osiem w hierarchii od Aderona do Pracownika i dwie poza nią (Księgowość, Kadry).
 * Klucz jest stały i ten sam w każdym sklepie (stanowiska i przesłana hierarchia łączą się po nim), a nazwę,
 * uprawnienia i kolejność sklep zmienia sam.
 */
public final class StoreRoleDefaults {

    public static final String ADERON = "ADERON";
    public static final String EMPLOYEE = "EMPLOYEE";

    public record Definition(String key, String name, String description, boolean admin, boolean outsideHierarchy,
            Map<PermissionCategory, PermissionLevel> permissions) {
    }

    public static final List<Definition> ALL = List.of(
            new Definition(ADERON, "Aderon", "Wszystko w obrębie sklepu (bez ustawień firmy)", true, false, Map.of()),
            new Definition("DIRECTOR", "Dyrektor", "Edycja wszystkich narzędzi sklepu", false, false,
                    all(EDIT)),
            new Definition("MANAGER", "Kierownik", "Edycja narzędzi sklepu, bez Kadr i zakładania kont", false, false,
                    manager()),
            new Definition("DEPUTY_MANAGER", "Zastępca kierownika", "Jak kierownik", false, false, manager()),
            new Definition("DEPARTMENT_LEADER", "Lider działu", "Praca narzędziami, grafik i czas pracy działu",
                    false, false, of(SALES, VIEW, INVENTORY, VIEW, ORDERS, VIEW, DELI_ORDERS, VIEW, DOCUMENTS, VIEW,
                            DATA_ENTRY, VIEW, SUPPLIERS, VIEW, DELI_PRODUCTION, VIEW, STAFF, VIEW,
                            STAFF_SCHEDULE, EDIT, STAFF_TIME, EDIT)),
            new Definition("PRODUCTION_MANAGER", "Zarządca produkcji", "Garmaż, karty produkcji, kalkulator cen i mięso",
                    false, false, of(DELI_ORDERS, EDIT, DELI_PRICES, EDIT, DELI_PRODUCTION, EDIT, MEAT_PURCHASES, EDIT,
                            INVENTORY, VIEW, SALES, VIEW, DATA_ENTRY, VIEW)),
            new Definition("IT", "Informatyk", "Import danych, wprowadzanie danych, KSeF i dziennik sklepu", false,
                    false, of(IMPORTS, EDIT, DATA_ENTRY, EDIT, KSEF_INVOICES, EDIT, INVENTORY, VIEW, STORE_LOG, VIEW)),
            new Definition(EMPLOYEE, "Pracownik", "Podgląd raportów i magazynu", false, false,
                    of(SALES, VIEW, INVENTORY, VIEW)),
            new Definition("ACCOUNTING", "Księgowość", "Faktury, dokumenty i raport kasowy (poza hierarchią)", false,
                    true, of(KSEF_INVOICES, EDIT, DOCUMENTS, EDIT, SUPPLIERS, VIEW, CASH_REPORT, EDIT, SALES, VIEW)),
            new Definition("HR", "Kadry", "Pracownicy, kadry i czas pracy (poza hierarchią)", false, true,
                    of(STAFF, EDIT, STAFF_RECORDS, EDIT, STAFF_SCHEDULE, VIEW, STAFF_TIME, EDIT)));

    private StoreRoleDefaults() {
    }

    /** Domyślna ranga o tej nazwie (bez wielkości liter i polskich znaków), np. dawna rola „Kierownik”. */
    public static Optional<Definition> byName(String name) {
        String wanted = normalize(name);
        return ALL.stream().filter(definition -> normalize(definition.name()).equals(wanted)).findFirst();
    }

    public static String normalize(String name) {
        String text = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l').replace('Ł', 'L');
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Map<PermissionCategory, PermissionLevel> all(PermissionLevel level) {
        Map<PermissionCategory, PermissionLevel> result = new EnumMap<>(PermissionCategory.class);
        for (PermissionCategory category : PermissionCategory.values()) {
            result.put(category, level);
        }
        // Wyniki wszystkich sklepów domyślnie ma tylko Aderon; innym rangom dokłada je sklep.
        result.remove(PermissionCategory.STORE_RESULTS);
        // Agentów na komputerach (klucz daje zapis plików w folderach sklepu) dodaje tylko ten, komu to nadano.
        result.remove(PermissionCategory.STORE_AGENT);
        return result;
    }

    private static Map<PermissionCategory, PermissionLevel> manager() {
        Map<PermissionCategory, PermissionLevel> result = all(EDIT);
        result.put(STAFF, VIEW);
        result.remove(STAFF_RECORDS);
        result.put(STORE_LOG, VIEW);
        return result;
    }

    private static Map<PermissionCategory, PermissionLevel> of(Object... pairs) {
        Map<PermissionCategory, PermissionLevel> result = new EnumMap<>(PermissionCategory.class);
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((PermissionCategory) pairs[i], (PermissionLevel) pairs[i + 1]);
        }
        return result;
    }
}
