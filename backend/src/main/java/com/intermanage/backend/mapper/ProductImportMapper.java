package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.EanChangeResponse;
import com.intermanage.backend.dto.ProductImportResponse;
import com.intermanage.backend.entity.ProductEanChange;
import com.intermanage.backend.entity.ProductImport;

@Mapper
public interface ProductImportMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    ProductImportResponse toResponse(ProductImport productImport);

    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "previousItm8", source = "previousProduct.itm8")
    @Mapping(target = "previousName", source = "previousProduct.name")
    EanChangeResponse toResponse(ProductEanChange change);
}
