package com.intermanage.backend.collector.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.collector.dto.StorePrinterResponse;
import com.intermanage.backend.collector.entity.StorePrinter;

@Mapper
public interface StorePrinterMapper {

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    StorePrinterResponse toResponse(StorePrinter printer);
}
