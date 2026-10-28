package com.intermanage.backend.pricecheck.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.pricecheck.dto.CadencierImportResponse;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.entity.CadencierImport;
import com.intermanage.backend.pricecheck.dto.PriceCheckSettingsResponse;
import com.intermanage.backend.pricecheck.entity.PriceCheckReview;
import com.intermanage.backend.pricecheck.entity.PriceCheckSettings;

@Mapper
public interface PriceCheckMapper {

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    PriceCheckReviewResponse toResponse(PriceCheckReview review);

    @Mapping(target = "importedBy", source = "importedBy.displayName")
    CadencierImportResponse toResponse(CadencierImport cadencierImport);

    /** {@code settings} pusty = sklep jeszcze nie ustawił minimalnej marży. */
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    PriceCheckSettingsResponse toResponse(PriceCheckSettings settings);
}
