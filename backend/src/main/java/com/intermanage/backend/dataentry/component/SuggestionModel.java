package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Model językowy proponujący nowym artykułom nazwę, markę, podrodzinę i jednostkę. Produkcyjnie Gemini
 * ({@link GeminiSuggestionModel}), w testach podmieniany.
 */
public interface SuggestionModel {

    /** Nazwa modelu zapisywana przy propozycji. */
    String name();

    /** Czy model jest skonfigurowany (np. jest klucz API); bez niego propozycje idą tylko z kartoteki. */
    boolean available();

    /**
     * Odpowiedzi dla paczki pozycji z {@code prompt}, w dowolnej kolejności, z {@code id} pozycji.
     *
     * @throws Unavailable gdy model nie odpowiedział (limit, przeciążenie, błąd), także po ponowieniach
     */
    List<Answer> suggest(String systemInstruction, String prompt);

    record Answer(int id, String name, String brand, String subfamily, List<String> alternatives, Double confidence,
            String unit, BigDecimal netContent) {
    }

    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
