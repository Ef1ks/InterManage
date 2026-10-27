package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.SalesImportResponse;
import com.intermanage.backend.entity.SalesImport;

@Mapper
public interface SalesMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SalesImportResponse toResponse(SalesImport salesImport);
}
