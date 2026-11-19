package com.intermanage.backend.document.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Typy dokumentów magazynowych z raportu Wejścia/Wyjścia ("Type Document"). Nazwa stałej to kod z systemu sklepu
 * i trafia do bazy, więc istniejących nie zmieniamy. W aplikacji pokazujemy tylko pełne nazwy, nie kody.
 * Typy spoza tej listy są przy imporcie pomijane.
 */
public enum DocumentType {

    PZ("Przyjęcia towaru", "Przyjęcie towaru", "Towar przyjęty od dostawców"),
    PK("Korekty błędów sklepu", "Korekta błędu sklepu", "Poprawki pomyłek popełnionych w sklepie"),
    WZ("Korekty od dostawcy", "Korekta od dostawcy", "Korekty dokumentów wystawione przez dostawcę"),
    PL("Likwidacje", "Likwidacja", "Towar zlikwidowany: przeterminowany, uszkodzony"),
    PPP("Przeniesienia stanów", "Przeniesienie stanów", "Przeniesienie ilości między artykułami"),
    ZW("Zwroty do dostawcy", "Zwrot do dostawcy", "Towar oddany dostawcy, np. niesprzedana prasa"),
    MM("Ruchy międzymagazynowe", "Ruch międzymagazynowy", "Towar wysłany do innego sklepu"),
    RWPO("Potrzeby sklepu", "Wydanie na potrzeby sklepu", "Towar zużyty na własne potrzeby sklepu"),
    RWREP("Reprezentacja", "Wydanie na reprezentację", "Towar wydany na cele reprezentacyjne"),
    RWDA("Darowizny", "Wydanie na darowiznę", "Towar przekazany jako darowizna"),
    /** Kod z raportów 06713 i 10024 (11.2026), linii jeszcze nie oglądaliśmy; najpewniej reklama (PROTO3). */
    RWREK("Reklama", "Wydanie na reklamę", "Towar wydany na cele reklamowe"),
    /**
     * Na próbce z 10023 (12-15.11.2026, 110 linii, 36 dokumentów, numery jak protokoły: NB0002720261113): ilość,
     * zakup i sprzedaż na minusie, głównie od bazy (538, 16538), rejon 55. Znaczenia kodu nie znamy.
     */
    NB("Zdjęcia ze stanu", "Zdjęcie ze stanu", "Towar zdjęty ze stanu: ilość i wartości na minusie"),
    /**
     * Kod z raportów 06713, 09575, 10023 (11.2026), dziesiątki tysięcy linii w miesiącu inwentury (06713, styczeń
     * 2025: ok. 128 tys.), więc najpewniej różnice inwentaryzacyjne; linii jeszcze nie oglądaliśmy.
     */
    PI("Inwentaryzacje", "Inwentaryzacja", "Różnice stanów z inwentaryzacji");

    private final String label;
    private final String singular;
    private final String description;

    DocumentType(String label, String singular, String description) {
        this.label = label;
        this.singular = singular;
        this.description = description;
    }

    /** Kod z raportu albo ze ścieżki API, bez względu na wielkość liter ("pz", "PZ"). */
    public static Optional<DocumentType> fromCode(String code) {
        String upper = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.name().equals(upper)).findFirst();
    }

    /** Przeniesienia stanów bywają gratisem od dostawcy i tylko je można tak zarejestrować. */
    public boolean canBeGratis() {
        return this == PPP;
    }

    /** Przyjęcia i korekty od dostawcy mają fakturę w KSeF (korekty - fakturę korygującą), więc można ją przypiąć. */
    public boolean canLinkKsefInvoice() {
        return this == PZ || this == WZ;
    }

    /**
     * Przyjęcia i ich korekty mogą mieć fakturę spoza KSeF wpisaną ręcznie (dostawca bez KSeF, faktura korygująca
     * do korekty błędu sklepu).
     */
    public boolean canHaveManualInvoice() {
        return this == PZ || isDeliveryCorrection();
    }

    /** Korekty błędów sklepu i od dostawcy poprawiają konkretne przyjęcie, więc przypina się je do niego. */
    public boolean isDeliveryCorrection() {
        return this == PK || this == WZ;
    }

    /**
     * Wydania ze sklepu: w raporcie cena i wartość zakupu mają minus (linia cofająca wydanie odwrotnie), a ilość
     * i sprzedaż plus.
     */
    public boolean isIssue() {
        return this == PL || this == MM || this == ZW || this == RWPO || this == RWREP || this == RWDA || this == RWREK;
    }

    public String label() {
        return label;
    }

    public String singular() {
        return singular;
    }

    public String description() {
        return description;
    }
}
