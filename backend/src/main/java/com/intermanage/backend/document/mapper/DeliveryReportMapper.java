package com.intermanage.backend.document.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.document.dto.DeliveryReportClosingResponse;
import com.intermanage.backend.document.dto.DeliveryReportExcludedSupplierResponse;
import com.intermanage.backend.document.entity.DeliveryReportClosing;
import com.intermanage.backend.document.entity.DeliveryReportExcludedSupplier;

@Mapper
public interface DeliveryReportMapper {

    @Mapping(target = "closedBy", source = "closing.closedBy.displayName")
    DeliveryReportClosingResponse toResponse(DeliveryReportClosing closing, int pendingChanges, boolean cancellable);

    @Mapping(target = "code", source = "supplierCode")
    @Mapping(target = "name", source = "supplierName")
    DeliveryReportExcludedSupplierResponse toResponse(DeliveryReportExcludedSupplier supplier);
}
