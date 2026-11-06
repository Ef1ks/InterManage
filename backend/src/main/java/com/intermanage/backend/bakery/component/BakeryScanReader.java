package com.intermanage.backend.bakery.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Odczyt skanu albo zdjęcia WZ piekarni przez model. Nie jest jeszcze podłączony ({@link NoBakeryScanReader}):
 * podłączenie to implementacja z {@code @Primary} (wzór: {@code GeminiMeatScanReader}), reszta drogi jest gotowa
 * i sprawdzona testem z podmienionym czytnikiem. Model dostaje ponumerowaną listę artykułów i wskazuje numery z niej.
 */
public interface BakeryScanReader {

    /** {@code false} ukrywa odczyt w interfejsie; skan można wtedy tylko przypiąć i obejrzeć. */
    boolean available();

    Reading read(byte[] content, String contentType, List<String> articles);

    record Reading(String documentNumber, LocalDate deliveryDate, List<Line> lines) {
    }

    /** {@code articleNo}: numer z listy artykułów (od 1, 0 = brak pasującego). */
    record Line(String name, BigDecimal quantity, String note, int articleNo) {
    }

    /** Model niedostępny (brak klucza, limit, przeciążenie, nieczytelna odpowiedź). */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
