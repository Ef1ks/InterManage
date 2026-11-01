package com.intermanage.backend.nomenclature.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Plik marż wymaganych: kod nomenklatury -> marża (pusta komórka = {@code null}, czyli bez progu). */
public record ParsedMarginFile(Map<String, BigDecimal> margins) {
}
