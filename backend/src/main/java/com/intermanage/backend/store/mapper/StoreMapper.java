package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dto.StoreResponse;
import com.intermanage.backend.dto.StoreSummary;
import com.intermanage.backend.entity.Store;

@Mapper
public interface StoreMapper {

    StoreResponse toResponse(Store store, long userCount);

    StoreSummary toSummary(Store store);
}
