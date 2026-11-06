package com.intermanage.backend.bakery.dto;

import java.util.List;
import java.util.UUID;

/**
 * Weryfikacja do edycji. {@code articles}: wiersze każdej tabeli WZ, czyli artykuły dostawcy z kartoteki i artykuły
 * z przypiętych przyjęć, po nazwie. {@code scanReading}: czy odczyt skanów przez AI jest podłączony.
 * {@code notices}: czego nie udało się zrobić przy tym zapisie (np. KSeF nie oddał XML faktury).
 */
public record BakeryCheckResponse(UUID id, UUID supplierId, String supplierName, String supplierNip,
        List<BakeryBon> bons, List<BakeryInvoice> invoices, List<BakeryWzResponse> wz, List<BakeryArticle> articles,
        boolean scanReading, List<String> notices) {
}
