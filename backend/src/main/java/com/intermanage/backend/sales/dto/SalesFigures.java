package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;

/**
 * Sprzedaż za okres: ilość (sztuki i kg razem, jak w raporcie), koszt zakupu FIFO netto, sprzedaż brutto i netto
 * po rabacie oraz marża FIFO. % marży i zmiany liczy front z tych sum, bo procentów nie da się sumować.
 */
public record SalesFigures(
        BigDecimal quantity,
        BigDecimal costValue,
        BigDecimal saleValueGross,
        BigDecimal saleValueNet,
        BigDecimal marginValue) {

    public static final SalesFigures ZERO = new SalesFigures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);

    public SalesFigures plus(SalesFigures other) {
        return new SalesFigures(quantity.add(other.quantity), costValue.add(other.costValue),
                saleValueGross.add(other.saleValueGross), saleValueNet.add(other.saleValueNet),
                marginValue.add(other.marginValue));
    }
}
