package com.intermanage.backend.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.CorrectedDelivery;
import com.intermanage.backend.dto.CorrectionTotals;
import com.intermanage.backend.dto.DeliveryCorrectionResponse;
import com.intermanage.backend.dto.DocumentGratisResponse;
import com.intermanage.backend.dto.DocumentImportResponse;
import com.intermanage.backend.dto.DocumentResponse;
import com.intermanage.backend.dto.DocumentResponse.DocumentLineResponse;
import com.intermanage.backend.dto.DocumentSummary;
import com.intermanage.backend.dto.DocumentTypeSummary;
import com.intermanage.backend.dto.InvoiceAmountCheck;
import com.intermanage.backend.dto.LinkedDeliOrder;
import com.intermanage.backend.dto.LinkedDelivery;
import com.intermanage.backend.dto.LinkedKsefInvoice;
import com.intermanage.backend.entity.DeliveryCorrection;
import com.intermanage.backend.entity.Document;
import com.intermanage.backend.entity.DocumentGratis;
import com.intermanage.backend.entity.DocumentImport;
import com.intermanage.backend.entity.DocumentLine;
import com.intermanage.backend.entity.DocumentType;

@Mapper
public interface DocumentMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    DocumentImportResponse toResponse(DocumentImport documentImport);

    /** {@code gratis} pusty, gdy dokumentu nie zarejestrowano jako gratis. */
    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "gratis", source = "gratis")
    @Mapping(target = "ksefInvoices", source = "ksefInvoices")
    @Mapping(target = "correctionTotals", source = "correctionTotals")
    @Mapping(target = "correctionOf", source = "correctionOf")
    @Mapping(target = "number", source = "document.number")
    @Mapping(target = "documentDate", source = "document.documentDate")
    @Mapping(target = "supplierName", source = "document.supplierName")
    @Mapping(target = "purchaseValue", source = "document.purchaseValue")
    @Mapping(target = "saleValueGross", source = "document.saleValueGross")
    @Mapping(target = "invoiceAmountCheck", source = "invoiceAmountCheck")
    DocumentSummary toSummary(Document document, DocumentGratis gratis, List<LinkedKsefInvoice> ksefInvoices,
            CorrectionTotals correctionTotals, LinkedDelivery correctionOf, InvoiceAmountCheck invoiceAmountCheck);

    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "gratis", source = "gratis")
    @Mapping(target = "typeLabel", expression = "java(document.getType().label())")
    @Mapping(target = "typeSingular", expression = "java(document.getType().singular())")
    @Mapping(target = "gratisAllowed", expression = "java(document.getType().canBeGratis())")
    @Mapping(target = "supplierDocumentNumberEditedBy", source = "document.supplierDocumentNumberEditedBy.displayName")
    @Mapping(target = "ksefLinkable", expression = "java(document.getType().canLinkKsefInvoice())")
    @Mapping(target = "ksefInvoices", source = "ksefInvoices")
    @Mapping(target = "supplierDocumentNumberEditable",
            expression = "java(document.getType() == com.intermanage.backend.entity.DocumentType.PZ)")
    @Mapping(target = "correctable",
            expression = "java(document.getType() == com.intermanage.backend.entity.DocumentType.PZ)")
    @Mapping(target = "attachableToDelivery", expression = "java(document.getType().isDeliveryCorrection())")
    @Mapping(target = "corrections", source = "corrections")
    @Mapping(target = "corrected", source = "corrected")
    @Mapping(target = "correctionTotals", source = "correctionTotals")
    @Mapping(target = "correctionOf", source = "correctionOf")
    @Mapping(target = "number", source = "document.number")
    @Mapping(target = "documentDate", source = "document.documentDate")
    @Mapping(target = "supplierName", source = "document.supplierName")
    @Mapping(target = "purchaseValue", source = "document.purchaseValue")
    @Mapping(target = "saleValueGross", source = "document.saleValueGross")
    @Mapping(target = "lines", source = "document.lines")
    @Mapping(target = "invoiceAmountCheck", source = "invoiceAmountCheck")
    DocumentResponse toResponse(Document document, List<LinkedDeliOrder> deliOrders, DocumentGratis gratis,
            List<LinkedKsefInvoice> ksefInvoices, List<DeliveryCorrectionResponse> corrections,
            CorrectedDelivery corrected, CorrectionTotals correctionTotals, LinkedDelivery correctionOf,
            InvoiceAmountCheck invoiceAmountCheck);

    /** Korekta przypięta do przyjęcia: dane dokumentu korekty i kto ją przypiął. */
    @Mapping(target = "id", source = "correction.id")
    @Mapping(target = "type", source = "correction.type")
    @Mapping(target = "typeSingular", expression = "java(link.getCorrection().getType().singular())")
    @Mapping(target = "number", source = "correction.number")
    @Mapping(target = "documentDate", source = "correction.documentDate")
    @Mapping(target = "lineCount", source = "correction.lineCount")
    @Mapping(target = "purchaseValue", source = "correction.purchaseValue")
    @Mapping(target = "saleValueGross", source = "correction.saleValueGross")
    @Mapping(target = "linkedBy", source = "linkedBy.displayName")
    DeliveryCorrectionResponse toCorrectionResponse(DeliveryCorrection link);

    LinkedDelivery toLinkedDelivery(Document delivery);

    @Mapping(target = "supplierId", source = "supplier.id")
    @Mapping(target = "supplierCode", source = "supplier.code")
    @Mapping(target = "supplierName", source = "supplier.name")
    @Mapping(target = "registeredBy", source = "registeredBy.displayName")
    DocumentGratisResponse toResponse(DocumentGratis gratis);

    DocumentLineResponse toResponse(DocumentLine line);

    default DocumentTypeSummary toSummary(DocumentType type, long documentCount, LocalDateTime lastDocumentDate) {
        return new DocumentTypeSummary(type.name(), type.label(), type.singular(), type.description(), documentCount,
                lastDocumentDate);
    }
}
