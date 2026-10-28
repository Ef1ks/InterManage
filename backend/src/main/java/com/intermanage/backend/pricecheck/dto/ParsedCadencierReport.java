package com.intermanage.backend.pricecheck.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Lista "OBECNE W CADENCIER" po odczytaniu: numer sklepu i data z nagłówka, unikalne ITM8 i ich EAN-y (te same indeksy). */
public record ParsedCadencierReport(String storeNumber, LocalDateTime generatedAt, List<String> itm8s,
        List<String> eans) {
}
