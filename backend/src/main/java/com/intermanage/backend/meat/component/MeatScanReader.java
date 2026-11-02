package com.intermanage.backend.meat.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Odczyt skanu albo zdjęcia dokumentu WZ dostawcy mięsa (w aplikacji Gemini, w testach podmieniany). Model dostaje
 * ponumerowane listy mięsa z kartoteki i dostawców sklepu i wskazuje numery z nich; 0 = nie wiadomo.
 */
public interface MeatScanReader {

    Reading read(byte[] content, String contentType, List<String> articles, List<String> suppliers);

    /**
     * {@code supplierNo}: numer z listy dostawców (od 1, 0 = nie wiadomo). Daty jak na dokumencie, {@code null}
     * gdy ich nie ma.
     */
    record Reading(String supplierName, String supplierNip, String supplierBdo, String supplierVetNumber,
            int supplierNo, String documentNumber, LocalDate issueDate, LocalDate shipDate, List<Line> lines) {
    }

    /** {@code articleNo}: numer z listy mięsa (od 1, 0 = brak pasującego artykułu). */
    record Line(String name, String code, BigDecimal unitNetPrice, boolean handwrittenCorrection, String note,
            int articleNo) {
    }

    /** Model niedostępny (brak klucza, limit, przeciążenie, nieczytelna odpowiedź). */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
