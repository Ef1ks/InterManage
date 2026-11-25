package com.intermanage.backend.collector.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.collector.dto.CollectorListResponse;
import com.intermanage.backend.collector.entity.CollectorList;
import com.intermanage.backend.collector.entity.CollectorListProtocol;

@Mapper
public interface CollectorListMapper {

    @Mapping(target = "collectorName", source = "list.collector.name")
    @Mapping(target = "protocols", source = "protocols")
    CollectorListResponse toResponse(CollectorList list, List<CollectorListProtocol> protocols);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    CollectorListResponse.Protocol toProtocol(CollectorListProtocol protocol);
}
