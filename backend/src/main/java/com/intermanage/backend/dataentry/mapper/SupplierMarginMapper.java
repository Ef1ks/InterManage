package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dataentry.dto.SupplierMarginResponse;
import com.intermanage.backend.dataentry.entity.SupplierMargin;

@Mapper
public interface SupplierMarginMapper {

    /** Wyjątek na nomenklaturze z nazwą pozycji z nomenklatury sklepu ({@code null}, gdy jej już nie ma). */
    @Mapping(target = "code", source = "margin.nomenclatureCode")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "marginPercent", source = "margin.marginPercent")
    SupplierMarginResponse.Rule toRule(SupplierMargin margin, String name);
}
