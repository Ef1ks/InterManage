package com.intermanage.backend.dto;

import java.math.BigDecimal;

/** Przyjęcie PZ po korektach: liczba przypiętych korekt i wartości dokumentu razem z nimi. */
public record CorrectionTotals(int count, BigDecimal purchaseValue, BigDecimal saleValueGross) {
}
