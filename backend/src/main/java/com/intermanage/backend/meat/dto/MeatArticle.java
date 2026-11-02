package com.intermanage.backend.meat.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Mięso z kartoteki sklepu do wyboru przy pozycji WZ; {@code salePrice} z ostatniego raportu produktów. */
public record MeatArticle(UUID id, String itm8, String ean, String name, String brand, String subfamilyCode,
        BigDecimal salePrice) {
}
