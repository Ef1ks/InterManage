package com.intermanage.backend.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.DocumentGratisResponse;
import com.intermanage.backend.dto.DocumentImportResponse;
import com.intermanage.backend.dto.DocumentResponse;
import com.intermanage.backend.dto.DocumentResponse.DocumentLineResponse;
import com.intermanage.backend.dto.DocumentSummary;
import com.intermanage.backend.dto.DocumentTypeSummary;
import com.intermanage.backend.dto.LinkedDeliOrder;
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
    DocumentSummary toSummary(Document document, DocumentGratis gratis);

    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "gratis", source = "gratis")
    @Mapping(target = "typeLabel", expression = "java(document.getType().label())")
    @Mapping(target = "typeSingular", expression = "java(document.getType().singular())")
    @Mapping(target = "gratisAllowed", expression = "java(document.getType().canBeGratis())")
    DocumentResponse toResponse(Document document, List<LinkedDeliOrder> deliOrders, DocumentGratis gratis);

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
