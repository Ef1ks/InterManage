package com.intermanage.backend.dataentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.DataEntryPromoPrice;

public interface DataEntryPromoPriceRepository extends JpaRepository<DataEntryPromoPrice, UUID> {

    List<DataEntryPromoPrice> findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID storeId);

    List<DataEntryPromoPrice> findByStoreIdAndEanInAndDeletedAtIsNull(UUID storeId, Collection<String> eans);

    List<DataEntryPromoPrice> findByStoreIdAndIdIn(UUID storeId, Collection<UUID> ids);
}
