package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dataentry.dto.PromoPriceItem;
import com.intermanage.backend.dataentry.entity.DataEntryPromoPrice;

@Mapper
public interface DataEntryPromoPriceMapper {

    PromoPriceItem toItem(DataEntryPromoPrice price);
}
