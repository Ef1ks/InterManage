package com.intermanage.backend.meat.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Odczyt skanu WZ do sprawdzenia przed zapisem (nic poza samym skanem nie jest jeszcze zapisane).
 *
 * @param supplier        rozpoznany dostawca z listy sklepu albo {@code null}
 * @param supplierMatch   skąd: {@code MARK} zapamiętany numer weterynaryjny / BDO / NIP, {@code NIP} NIP z dokumentu,
 *                        {@code MODEL} wybór modelu (do sprawdzenia)
 * @param deliveryDate    data wysyłki z dokumentu, bez niej data wystawienia
 * @param duplicateId     ten dokument tego dostawcy jest już wprowadzony
 */
public record MeatScanResult(UUID scanId, String contentType, MeatSupplier supplier, String supplierMatch,
        String readSupplierName, String readNip, String readBdo, String readVetNumber, String documentNumber,
        LocalDate deliveryDate, UUID duplicateId, List<Line> lines) {

    /**
     * @param article   artykuł z kartoteki albo {@code null}
     * @param match     {@code LINK} zapamiętane przypisanie u tego dostawcy, {@code MODEL} propozycja modelu,
     *                  {@code NONE} brak
     * @param noArticle zapamiętane jako pozycja bez artykułu (sam zakup)
     */
    public record Line(String documentName, String documentCode, BigDecimal unitPrice, boolean handwrittenCorrection,
            String note, MeatArticle article, String match, boolean noArticle) {
    }
}
