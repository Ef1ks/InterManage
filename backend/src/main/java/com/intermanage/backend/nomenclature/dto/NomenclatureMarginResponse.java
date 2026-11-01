package com.intermanage.backend.nomenclature.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;

/**
 * Pozycja nomenklatury z marżą wymaganą ustawioną na niej ({@code marginPercent}, pusta = bez progu na tym poziomie)
 * i marżą, która na niej obowiązuje ({@code effectivePercent}: własna albo z rodziny / rejonu).
 */
public record NomenclatureMarginResponse(
        String code,
        String name,
        NomenclatureLevel level,
        BigDecimal marginPercent,
        BigDecimal effectivePercent,
        String updatedBy,
        Instant updatedAt) {
}
