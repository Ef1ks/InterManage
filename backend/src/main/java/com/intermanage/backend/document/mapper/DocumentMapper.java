package com.intermanage.backend.document.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.CorrectionTotals;
import com.intermanage.backend.document.dto.DeliveryCorrectionResponse;
import com.intermanage.backend.document.dto.DocumentGratisResponse;
import com.intermanage.backend.document.dto.DocumentImportResponse;
import com.intermanage.backend.document.dto.DocumentResponse.DocumentLineResponse;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.DocumentSummary;
import com.intermanage.backend.document.dto.DocumentTypeSummary;
import com.intermanage.backend.document.dto.InvoiceAmountCheck;
import com.intermanage.backend.document.dto.LinkedDeliOrder;
import com.intermanage.backend.document.dto.LinkedDelivery;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.dto.ManualInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratis;
import com.intermanage.backend.document.entity.DocumentImport;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentManualInvoice;
import com.intermanage.backend.document.entity.DocumentType;

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
    @Mapping(target = "manualInvoices", source = "manualInvoices")
    DocumentSummary toSummary(Document document, DocumentGratis gratis, List<LinkedKsefInvoice> ksefInvoices,
            CorrectionTotals correctionTotals, LinkedDelivery correctionOf, InvoiceAmountCheck invoiceAmountCheck,
            List<ManualInvoice> manualInvoices);

    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "gratis", source = "gratis")
    @Mapping(target = "typeLabel", expression = "java(document.getType().label())")
    @Mapping(target = "typeSingular", expression = "java(document.getType().singular())")
    @Mapping(target = "gratisAllowed", expression = "java(document.getType().canBeGratis())")
    @Mapping(target = "supplierDocumentNumberEditedBy", source = "document.supplierDocumentNumberEditedBy.displayName")
    @Mapping(target = "ksefLinkable", expression = "java(document.getType().canLinkKsefInvoice())")
    @Mapping(target = "ksefInvoices", source = "ksefInvoices")
    @Mapping(target = "supplierDocumentNumberEditable",
            expression = "java(document.getType() == com.intermanage.backend.document.entity.DocumentType.PZ)")
    @Mapping(target = "correctable",
            expression = "java(document.getType() == com.intermanage.backend.document.entity.DocumentType.PZ)")
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
    @Mapping(target = "manualInvoiceAllowed", expression = "java(document.getType().canHaveManualInvoice())")
    @Mapping(target = "manualInvoices", source = "manualInvoices")
    DocumentResponse toResponse(Document document, List<LinkedDeliOrder> deliOrders, DocumentGratis gratis,
            List<LinkedKsefInvoice> ksefInvoices, List<DeliveryCorrectionResponse> corrections,
            CorrectedDelivery corrected, CorrectionTotals correctionTotals, LinkedDelivery correctionOf,
            InvoiceAmountCheck invoiceAmountCheck, List<ManualInvoice> manualInvoices);

    @Mapping(target = "supplierId", source = "supplier.id")
    @Mapping(target = "supplierName", source = "supplier.name")
    @Mapping(target = "createdBy", source = "createdBy.displayName")
    ManualInvoice toManualInvoice(DocumentManualInvoice invoice);

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
