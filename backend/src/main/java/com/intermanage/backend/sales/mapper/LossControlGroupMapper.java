package com.intermanage.backend.sales.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.sales.dto.LossControlGroupResponse;
import com.intermanage.backend.sales.entity.LossControlGroup;

@Mapper
public interface LossControlGroupMapper {

    @Mapping(target = "supplierId", source = "group.supplier.id")
    @Mapping(target = "supplierCode", source = "group.supplier.code")
    @Mapping(target = "supplierName", source = "group.supplier.name")
    LossControlGroupResponse toResponse(LossControlGroup group, List<String> itm8s);
}
