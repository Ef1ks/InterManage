package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.SupplierImportResponse;
import com.intermanage.backend.dto.SupplierResponse;
import com.intermanage.backend.entity.Supplier;
import com.intermanage.backend.entity.SupplierImport;

@Mapper
public interface SupplierMapper {

    SupplierResponse toResponse(Supplier supplier);

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SupplierImportResponse toResponse(SupplierImport supplierImport);
}
