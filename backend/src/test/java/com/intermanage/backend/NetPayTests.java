package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.component.NetPay;

/** Szacunek netto na przykładach z kalkulatorów wynagrodzeń 2026 (płaca minimalna 4806 zł). */
class NetPayTests {

    private static final NetPay.Settings STANDARD = new NetPay.Settings(true, false, false, false, false, true);

    @Test
    void minimumWageOnEmploymentContract() {
        NetPay.Result result = NetPay.employment(new BigDecimal("4806.00"), BigDecimal.ZERO, STANDARD);

        assertThat(result.social()).isEqualByComparingTo("658.91");
        assertThat(result.health()).isEqualByComparingTo("373.24");
        assertThat(result.tax()).isEqualByComparingTo("168");
        assertThat(result.net()).isEqualByComparingTo("3605.85");
    }

    @Test
    void withoutPit2TheTaxIsHigherBy300() {
        NetPay.Result result = NetPay.employment(new BigDecimal("4806.00"), BigDecimal.ZERO,
                new NetPay.Settings(false, false, false, false, false, true));

        assertThat(result.tax()).isEqualByComparingTo("468");
        assertThat(result.net()).isEqualByComparingTo("3305.85");
    }

    @Test
    void youthReliefLeavesNoTax() {
        NetPay.Result result = NetPay.employment(new BigDecimal("4806.00"), BigDecimal.ZERO,
                new NetPay.Settings(true, false, true, false, false, true));

        assertThat(result.tax()).isEqualByComparingTo("0");
        assertThat(result.net()).isEqualByComparingTo("3773.85");
    }

    @Test
    void sickPayHasNoSocialContributions() {
        NetPay.Result result = NetPay.employment(BigDecimal.ZERO, new BigDecimal("1000.00"), STANDARD);

        assertThat(result.social()).isEqualByComparingTo("0");
        assertThat(result.health()).isEqualByComparingTo("90.00");
        // (1000 - 250) * 12% = 90, minus 300 = poniżej zera.
        assertThat(result.tax()).isEqualByComparingTo("0");
        assertThat(result.net()).isEqualByComparingTo("910.00");
    }

    @Test
    void mandateWithoutSicknessAndStudentMandate() {
        NetPay.Result mandate = NetPay.mandate(new BigDecimal("1000.00"), STANDARD, false);
        // 97,60 + 15,00 społeczne, 9% z 887,40 zdrowotna, koszty 20% = 177,48, podstawa 710, 85,20 - 300 = 0.
        assertThat(mandate.social()).isEqualByComparingTo("112.60");
        assertThat(mandate.health()).isEqualByComparingTo("79.87");
        assertThat(mandate.tax()).isEqualByComparingTo("0");
        assertThat(mandate.net()).isEqualByComparingTo("807.53");

        NetPay.Result student = NetPay.mandate(new BigDecimal("1000.00"),
                new NetPay.Settings(true, false, true, true, false, true), false);
        assertThat(student.net()).isEqualByComparingTo("1000.00");
    }

    @Test
    void mandateAtOwnEmployerPaysFullContributionsWithoutReduction() {
        NetPay.Result result = NetPay.mandate(new BigDecimal("1000.00"), STANDARD, true);

        assertThat(result.social()).isEqualByComparingTo("137.10");
        // podstawa (1000 - 137,10) * 0,8 = 690,32 -> 690, 12% = 82,80 -> 83, bez kwoty zmniejszającej.
        assertThat(result.tax()).isEqualByComparingTo("83");
    }
}
