package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dataentry.dto.BaseSupplierCodeItem;
import com.intermanage.backend.dataentry.dto.SkippedSupplier;
import com.intermanage.backend.dataentry.entity.DataEntrySkippedSupplier;
import com.intermanage.backend.ksef.entity.KsefBaseSupplierCode;

@Mapper
public interface DataEntryInvoiceMapper {

    SkippedSupplier toResponse(DataEntrySkippedSupplier supplier);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    BaseSupplierCodeItem.Assignment toAssignment(KsefBaseSupplierCode code);
}
