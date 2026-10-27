package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.NomenclatureImportResponse;
import com.intermanage.backend.entity.NomenclatureImport;

@Mapper
public interface NomenclatureMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    NomenclatureImportResponse toResponse(NomenclatureImport nomenclatureImport);
}
