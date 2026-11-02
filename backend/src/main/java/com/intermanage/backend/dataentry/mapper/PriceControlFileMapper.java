package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dataentry.dto.PriceControlFileItem;
import com.intermanage.backend.dataentry.repository.DataEntryPriceControlFileRepository;

@Mapper
public interface PriceControlFileMapper {

    PriceControlFileItem toItem(DataEntryPriceControlFileRepository.Summary summary);
}
