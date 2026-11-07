package com.intermanage.backend.stocktake.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.stocktake.dto.StocktakeImportResponse;
import com.intermanage.backend.stocktake.entity.StocktakeImport;

@Mapper
public interface StocktakeMapper {

    @Mapping(target = "stocktakeId", source = "stocktake.id")
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    StocktakeImportResponse toResponse(StocktakeImport stocktakeImport);
}
