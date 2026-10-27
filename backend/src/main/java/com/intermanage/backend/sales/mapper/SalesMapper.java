package com.intermanage.backend.sales.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.sales.dto.SalesImportResponse;
import com.intermanage.backend.sales.entity.SalesImport;

@Mapper
public interface SalesMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SalesImportResponse toResponse(SalesImport salesImport);
}
