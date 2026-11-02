package com.intermanage.backend.product.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Odczytany plik TRACEART: czas z nazwy pliku, liczba linii i linie, które dało się odczytać. */
public record ParsedPriceFile(LocalDateTime fileTime, int totalLines, List<PriceFileLine> lines) {
}
