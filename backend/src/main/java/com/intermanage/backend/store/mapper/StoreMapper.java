package com.intermanage.backend.store.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.store.dto.StoreResponse;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;

@Mapper
public interface StoreMapper {

    StoreResponse toResponse(Store store, long userCount);

    StoreSummary toSummary(Store store);
}
