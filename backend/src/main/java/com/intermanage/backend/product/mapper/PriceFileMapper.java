package com.intermanage.backend.product.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.product.dto.PriceFileImportResponse;
import com.intermanage.backend.product.entity.PriceFileImport;

@Mapper
public interface PriceFileMapper {

    @Mapping(target = "importedBy", source = "importedBy.displayName")
    PriceFileImportResponse toResponse(PriceFileImport priceFile);
}
