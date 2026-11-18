package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.sales.component.InStockSales;

/** Sprzedaż w dniach na stanie: od dostawy do ostatniej sprzedaży przed kolejną dostawą. */
class InStockSalesTests {

    private final LocalDate day = LocalDate.of(2026, 10, 1);

    @Test
    void skipsDaysWithoutStockBetweenDeliveries() {
        TreeMap<LocalDate, BigDecimal> sales = new TreeMap<>();
        // Po pierwszej dostawie 4 dni sprzedaży po 2 szt., potem 20 dni bez towaru.
        for (int i = 0; i < 4; i++) {
            sales.put(day.plusDays(i), new BigDecimal("2"));
        }
        // Po drugiej dostawie sprzedaż w 1. i 3. dniu: na stanie 3 dni.
        sales.put(day.plusDays(24), BigDecimal.ONE);
        sales.put(day.plusDays(26), BigDecimal.ONE);

        InStockSales.Result result = InStockSales.of(List.of(day, day.plusDays(24)), sales, day.plusDays(40));

        assertThat(result.days()).isEqualTo(7);
        assertThat(result.sold()).isEqualByComparingTo("10");
        assertThat(result.weekly()).isEqualByComparingTo("10.00");
    }

    @Test
    void deliveryWithoutSalesGivesNoPeriod() {
        TreeMap<LocalDate, BigDecimal> sales = new TreeMap<>();
        sales.put(day.minusDays(3), BigDecimal.ONE);

        assertThat(InStockSales.of(List.of(day), sales, day.plusDays(10))).isNull();
    }
}
