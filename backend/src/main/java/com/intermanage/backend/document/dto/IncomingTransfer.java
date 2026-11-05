package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.store.dto.StoreSummary;

/**
 * Ruch międzymagazynowy innego sklepu wystawiony dla tego sklepu, do wprowadzenia u siebie. {@code id} to id wpisu,
 * nie dokumentu (dokument należy do sklepu wysyłającego). Linie tylko w szczegółach, na liście pusta lista.
 * {@code received}: ilości, które naprawdę przyjechały, wpisane przez ten sklep (linia bez wpisu = ilość z dokumentu).
 * Linie i wartości są po poprawach sklepu wysyłającego ({@code corrections}: numery poprawek). {@code receipt}:
 * dokument tego sklepu potwierdzający wprowadzenie.
 */
public record IncomingTransfer(
        UUID id,
        StoreSummary fromStore,
        String number,
        LocalDateTime documentDate,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        String createdBy,
        Instant createdAt,
        String enteredBy,
        Instant enteredAt,
        String receivedBy,
        Instant receivedAt,
        TransferReceiptResponse receipt,
        List<String> corrections,
        List<DocumentResponse.DocumentLineResponse> lines,
        List<ReceivedQuantity> received) {

    public record ReceivedQuantity(UUID lineId, BigDecimal quantity) {
    }
}
