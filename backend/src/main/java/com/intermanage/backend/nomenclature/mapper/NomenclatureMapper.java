package com.intermanage.backend.nomenclature.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.nomenclature.dto.NomenclatureImportResponse;
import com.intermanage.backend.nomenclature.entity.NomenclatureImport;

@Mapper
public interface NomenclatureMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    NomenclatureImportResponse toResponse(NomenclatureImport nomenclatureImport);
}
