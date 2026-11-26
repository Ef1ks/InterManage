package com.intermanage.backend.product.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.product.dto.DepositKindImportResponse;
import com.intermanage.backend.product.dto.DepositKindResponse;
import com.intermanage.backend.product.entity.DepositKind;
import com.intermanage.backend.product.entity.DepositKindImport;

@Mapper
public interface DepositKindMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    DepositKindImportResponse toResponse(DepositKindImport depositKindImport);

    DepositKindResponse toResponse(DepositKind depositKind);
}
