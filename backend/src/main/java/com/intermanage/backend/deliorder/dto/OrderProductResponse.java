package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.intermanage.backend.entity.OrderUnit;

/** Produkt w kategorii formularza. {@code id} to pozycja w kategorii, {@code productId} to produkt z kartoteki. */
public record OrderProductResponse(
        UUID id,
        UUID productId,
        String itm8,
        String ean,
        String name,
        String brand,
        String unit,
        BigDecimal salePrice,
        Boolean active,
        /** W czym zamawia się ten produkt (ustawia osoba układająca formularz). */
        OrderUnit orderUnit) {
}
