package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Dokument ze wszystkimi liniami; {@code typeSingular} to pełna nazwa do nagłówka ("Likwidacja"). */
public record DocumentResponse(
        UUID id,
        String type,
        String typeLabel,
        String typeSingular,
        String number,
        LocalDateTime documentDate,
        String supplierCode,
        String supplierName,
        String supplierDocumentNumber,
        /** Numer z raportu; różni się od obowiązującego, gdy ktoś go poprawił ręcznie. */
        String reportSupplierDocumentNumber,
        /** Ręczna poprawka numeru dokumentu dostawcy: kiedy i kto ({@code null} = numer z raportu). */
        Instant supplierDocumentNumberEditedAt,
        String supplierDocumentNumberEditedBy,
        /** Numer dokumentu dostawcy można poprawić (przyjęcia PZ, po nim faktury KSeF wiążą się z dostawą). */
        boolean supplierDocumentNumberEditable,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        /** Typ dokumentu można rejestrować jako gratis (przeniesienia stanów). */
        boolean gratisAllowed,
        boolean gratisCandidate,
        DocumentGratisResponse gratis,
        /** Do dokumentu można przypiąć fakturę z KSeF (przyjęcia i korekty od dostawcy). */
        boolean ksefLinkable,
        /**
         * Faktury KSeF: przypięte i dopasowane po numerze dokumentu dostawcy; przy przyjęciu także faktury jego korekt
         * od dostawcy ({@code viaCorrection}).
         */
        List<LinkedKsefInvoice> ksefInvoices,
        /** Do przyjęcia PZ można przypinać korekty, a jego wartości i wydruk bonu liczą się razem z nimi. */
        boolean correctable,
        /** Korekty przypięte do przyjęcia, od najstarszej. */
        List<DeliveryCorrectionResponse> corrections,
        /** Przyjęcie po korektach; {@code null}, gdy nie ma przypiętych korekt. */
        CorrectedDelivery corrected,
        CorrectionTotals correctionTotals,
        /** Korektę (błędu sklepu, od dostawcy) można przypiąć do przyjęcia, którego dotyczy. */
        boolean attachableToDelivery,
        /** Przyjęcie, do którego przypięto tę korektę. */
        LinkedDelivery correctionOf,
        /** Przyjęcie z fakturą: porównanie kwot netto z fakturami ({@code null} bez faktury). */
        InvoiceAmountCheck invoiceAmountCheck,
        /** Do dokumentu można wpisać fakturę spoza KSeF (przyjęcia i ich korekty). */
        boolean manualInvoiceAllowed,
        /** Faktury spoza KSeF wpisane ręcznie. */
        List<ManualInvoice> manualInvoices,
        /** Ostatnia zmiana nagłówka albo linii przez import. */
        Instant updatedAt,
        List<DocumentLineResponse> lines,
        /** Zamówienia garmażu jadące na tym dokumencie (tylko przy ruchach międzymagazynowych). */
        List<LinkedDeliOrder> deliOrders) {

    public record DocumentLineResponse(
            UUID id,
            int lineNumber,
            String supplierCode,
            String supplierName,
            String nomenclatureCode,
            String nomenclatureName,
            String itm8,
            String ean,
            String name,
            String brand,
            String unit,
            BigDecimal quantity,
            BigDecimal purchasePrice,
            BigDecimal salePriceCurrent,
            BigDecimal salePriceDocument,
            BigDecimal purchaseValue,
            BigDecimal saleValueGross,
            BigDecimal saleValueNet,
            Short vatRate,
            BigDecimal vatValue,
            BigDecimal marginValue,
            BigDecimal marginPercent) {
    }
}
