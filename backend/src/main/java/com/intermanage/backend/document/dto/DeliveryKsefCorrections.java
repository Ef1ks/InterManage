package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Korekty z KSeF faktury przyjęcia PZ (po fakturze korygowanej z XML korekty). {@code pendingCount}: ile korekt
 * dostawcy faktury czeka jeszcze na pobranie XML (dopiero wtedy wiadomo, co korygują).
 */
public record DeliveryKsefCorrections(List<Correction> corrections, long pendingCount) {

    /**
     * Korekta z KSeF: {@code correctedInvoiceNumber} to faktura przyjęcia, którą koryguje, {@code documents} - korekty
     * od dostawcy (WZ) sklepu z tą fakturą, {@code candidates} - podpowiedzi WZ, gdy żadna jej nie ma.
     */
    public record Correction(
            UUID id,
            String ksefNumber,
            String invoiceNumber,
            LocalDate issueDate,
            String sellerName,
            BigDecimal netAmount,
            BigDecimal grossAmount,
            String correctedInvoiceNumber,
            List<LinkedDocument> documents,
            List<Candidate> candidates) {
    }

    /**
     * Korekta od dostawcy z fakturą korygującą. {@code attached}: przypięta do tego przyjęcia; {@code otherDeliveryBon}:
     * bon innego przyjęcia, do którego ją przypięto.
     */
    public record LinkedDocument(UUID id, String type, String typeSingular, String number, LocalDateTime documentDate,
            BigDecimal purchaseValue, boolean attached, String otherDeliveryBon) {
    }

    /**
     * Korekta od dostawcy (WZ) pasująca do korekty z KSeF: ten sam dostawca, data blisko daty faktury, wartość
     * zakupu blisko kwoty netto ({@code difference}). {@code attached}: już przypięta do tego przyjęcia.
     */
    public record Candidate(UUID id, String number, LocalDateTime documentDate, BigDecimal purchaseValue,
            BigDecimal difference, boolean attached) {
    }
}
