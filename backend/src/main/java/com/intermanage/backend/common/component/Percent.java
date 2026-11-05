package com.intermanage.backend.common.component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Procenty w raportach. */
public final class Percent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Percent() {
    }

    /** Ile procent {@code whole} stanowi {@code part}, do 2 miejsc; {@code null}, gdy {@code whole} jest zerem. */
    public static BigDecimal of(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null : part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
