package com.intermanage.backend.deliprice.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Kalkulacja do pliku CADBEZP: cena zakupu z kartoteki i z kalkulacji, obie za sprzedawaną sztukę, oraz dostawca
 * z kartoteki (osobny plik na dostawcę). {@code missingPriceCount} > 0: koszt jest zaniżony, {@code countedInPieces}:
 * artykuł na sztuki, a koszt kalkulacji jest na 1 kg, więc to nie jest cena sztuki.
 */
public record PurchaseFileLine(
        UUID calculationId,
        String name,
        String ean,
        String supplier,
        BigDecimal currentPrice,
        BigDecimal purchasePrice,
        int missingPriceCount,
        boolean countedInPieces) {
}
