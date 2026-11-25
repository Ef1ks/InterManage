package com.intermanage.backend.store.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Domyślne ścieżki folderów ustalone przez aplikację (dysk sieciowy ma w każdym sklepie tę samą budowę, wzór: sklep
 * 10023). Sklep może wpisać własną ścieżkę, która obowiązuje tylko w nim; pusta albo równa domyślnej = domyślna.
 * Klucze to klucze folderów z przeglądarki i agenta; foldery ATHOS mają w kluczu id sklepu ({@link #key}).
 */
public final class DefaultFolders {

    public static final String PROTO = "deli-order-proto";
    public static final String PRODUCT_FILE = "data-entry-product-file";
    /** Bony dostawy z bazy zapisywane z Mercalys makrem (agent wysyła je do „Znajdź różnice”). */
    public static final String BASE_NOTES = "base-delivery-notes";

    /** Klucz bez sklepu → domyślna ścieżka, w kolejności strony „Ścieżki folderów”. */
    public static final Map<String, String> PATHS;
    /**
     * Domyślne ścieżki plików (klucz ze ścieżek sklepu, nie folder): zapis listy dostawców z Mercalys, żeby codzienny
     * raport działał w każdym sklepie bez ustawiania.
     */
    public static final Map<String, String> FILES = Map.of("import:SUPPLIERS:path",
            "P:\\Production\\INTERMANAGE\\DOSTAWCY\\DOSTAWCY.xlsx");
    /** Kolejność kluczy (Map.copyOf jej nie trzyma). */
    public static final List<String> ORDER;

    static {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put(PROTO, "N:\\AVISOCONFIG\\BACKUP");
        paths.put(PRODUCT_FILE, "H:\\GCOM\\INTERMANAGE\\CAD_BEZP");
        paths.put("data-entry-xml", "H:\\GCOM\\Aviso\\BLdirect");
        paths.put("data-entry-lst", "N:\\ALOA\\LST\\IMPORT");
        paths.put("leaflet-pro", "N:\\ALOA\\PRO\\IMPORT");
        paths.put("base-delivery-note-file", "N:\\ALOA\\LIVR\\IMPORT");
        paths.put("base-delivery-plu", "N:\\AVISOCONFIG\\BACKUP");
        paths.put(BASE_NOTES, "P:\\Production\\INTERMANAGE\\EXCEL");
        paths.put("mea-comrnis", "N:\\comrnis");
        paths.put("price-files", "N:\\AVISOCONFIG\\BACKUP");
        paths.put("mea-athos", "N:\\ATHOS2000");
        PATHS = Map.copyOf(paths);
        ORDER = List.copyOf(paths.keySet());
    }

    private DefaultFolders() {
    }

    /** Klucz folderu w sklepie: foldery ATHOS z id sklepu, jak w przeglądarce. */
    public static String key(String baseKey, UUID storeId) {
        return baseKey.startsWith("mea-") ? baseKey + ":" + storeId : baseKey;
    }

    /** Klucz bez id sklepu. */
    public static String baseKey(String key) {
        return key.replaceFirst("^(mea-athos|mea-comrnis):.*$", "$1");
    }

    /** Domyślna ścieżka folderu ({@code null}, gdy aplikacja jej nie ustala, np. foldery raportów importu). */
    public static String of(String key) {
        return PATHS.get(baseKey(key));
    }

    /** Ten sam folder bez względu na wielkość liter i końcowy ukośnik. */
    public static boolean same(String a, String b) {
        return a != null && b != null && normalized(a).equals(normalized(b));
    }

    public static String normalized(String path) {
        return path.trim().replaceAll("\\\\+$", "").toLowerCase(Locale.ROOT);
    }
}
