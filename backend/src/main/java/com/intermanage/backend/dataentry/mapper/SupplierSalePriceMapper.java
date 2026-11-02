package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dataentry.dto.SupplierSalePriceResponse;
import com.intermanage.backend.dataentry.entity.SupplierSalePrice;

@Mapper
public interface SupplierSalePriceMapper {

    @Mapping(target = "supplierId", source = "supplier.id")
    @Mapping(target = "supplierName", source = "supplier.name")
    @Mapping(target = "supplierCode", source = "supplier.code")
    @Mapping(target = "createdBy", source = "createdBy.displayName")
    SupplierSalePriceResponse toResponse(SupplierSalePrice price);
}
