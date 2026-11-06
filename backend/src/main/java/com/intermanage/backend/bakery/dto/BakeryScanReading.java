package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Odczyt skanu WZ: propozycja do wpisania w tabelę, niczego nie zapisuje. {@code productId} z listy artykułów
 * weryfikacji; {@code null}, gdy model nie dopasował pozycji.
 */
public record BakeryScanReading(String number, LocalDate deliveryDate, List<Line> lines) {

    public record Line(String name, BigDecimal quantity, String note, UUID productId) {
    }
}
