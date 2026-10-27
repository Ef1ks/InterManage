package com.intermanage.backend.entity;

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
    RWREP("Reprezentacja", "Wydanie na reprezentację", "Towar wydany na cele reprezentacyjne");

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

    /** Korekty błędów sklepu i od dostawcy poprawiają konkretne przyjęcie, więc przypina się je do niego. */
    public boolean isDeliveryCorrection() {
        return this == PK || this == WZ;
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
