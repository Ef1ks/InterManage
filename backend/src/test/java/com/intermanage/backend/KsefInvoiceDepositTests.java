package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.component.KsefInvoiceReader.Line;

class KsefInvoiceDepositTests {

    @Test
    void depositIsNamedAndCostsFiftyGroszPerPiece() {
        assertThat(line("KAUCJA BUTELKA", "24", "0.50", "12.00", false).deposit()).isTrue();
        assertThat(line("Kaucja za butelkę", "10", "0.41", "4.07", false).deposit()).isFalse();
        assertThat(line("Skrzynki zwrotne - kaucja", "12.5", "8.00", "100.00", false).deposit()).isFalse();
        assertThat(line("Guma do żucia", "10", "0.50", "5.00", false).deposit()).isFalse();
    }

    @Test
    void grossPriceOfFiftyGroszAlsoCounts() {
        Line line = new Line("1", "Kaucja", List.of(), new BigDecimal("10"), "szt", new BigDecimal("0.41"),
                new BigDecimal("0.50"), null, "23", new BigDecimal("4.07"), null, new BigDecimal("5.00"), false, false,
                null);
        assertThat(line.deposit()).isTrue();
    }

    @Test
    void depositNetSubtractsLinesBeforeCorrection() {
        assertThat(KsefInvoiceReader.depositNet(List.of(line("Kaucja", "24", "0.50", "12.00", false),
                line("Kaucja", "10", "0.50", "5.00", true), line("Mleko", "10", "2.00", "20.00", false))))
                .isEqualByComparingTo("7.00");
    }

    private static Line line(String name, String quantity, String unitNet, String net, boolean beforeCorrection) {
        return new Line("1", name, List.of(), new BigDecimal(quantity), "szt", new BigDecimal(unitNet), null, null, "23",
                new BigDecimal(net), null, null, beforeCorrection, false, null);
    }
}
