package com.intermanage.backend.pricecheck.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Nowa lista nomenklatur pomijanych w raporcie weryfikującym zakupy (kody 2, 4 albo 6 cyfr). */
public record ReportExcludedNomenclaturesRequest(
        @NotNull(message = "Podaj listę nomenklatur")
        List<@NotNull @Pattern(regexp = "([0-9]{2}){1,3}", message = "Kod nomenklatury ma 2, 4 albo 6 cyfr") String> codes) {
}
