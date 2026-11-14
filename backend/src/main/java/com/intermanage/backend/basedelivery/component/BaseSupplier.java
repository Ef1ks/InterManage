package com.intermanage.backend.basedelivery.component;

import java.util.Set;

import com.intermanage.backend.basedelivery.entity.BaseDeliveryLine;
import com.intermanage.backend.dataentry.dto.CatalogProduct;

/**
 * Dostawca linii dostawy z bazy. Alkohol (rejony 70-73) zakłada się i przyjmuje w Mercalys od dostawcy 88110, a plik
 * bazy potrafi mieć przy nim w Spolka 538 (bon 09516052153 w 10024), więc rejon ma pierwszeństwo przed plikiem. Rejon
 * z pliku, bez niego z kartoteki.
 */
public final class BaseSupplier {

    public static final String ALCOHOL = "88110";

    private static final Set<String> ALCOHOL_REGIONS = Set.of("70", "71", "72", "73");

    private BaseSupplier() {
    }

    public static String of(BaseDeliveryLine line, CatalogProduct product) {
        String region = line.getRegionCode();
        if ((region == null || region.isBlank()) && product != null && product.subfamilyCode() != null
                && product.subfamilyCode().length() >= 2) {
            region = product.subfamilyCode().substring(0, 2);
        }
        return region != null && ALCOHOL_REGIONS.contains(region) ? ALCOHOL : line.getSupplierCode();
    }
}
