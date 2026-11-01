package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dataentry.dto.SkippedSupplier;
import com.intermanage.backend.dataentry.entity.DataEntrySkippedSupplier;

@Mapper
public interface DataEntryInvoiceMapper {

    SkippedSupplier toResponse(DataEntrySkippedSupplier supplier);
}
