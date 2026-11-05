package com.intermanage.backend.pricecheck.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.pricecheck.dto.CadencierImportResponse;
import com.intermanage.backend.pricecheck.dto.IncidentImportResponse;
import com.intermanage.backend.pricecheck.dto.LowMarginReport;
import com.intermanage.backend.pricecheck.entity.LowMarginExcludedSupplier;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.pricecheck.entity.IncidentImport;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryResponse;
import com.intermanage.backend.pricecheck.entity.PriceCheckCategory;
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

    @Mapping(target = "importedBy", source = "importedBy.displayName")
    IncidentImportResponse toResponse(IncidentImport incidentImport);

    PriceCheckCategoryResponse toResponse(PriceCheckCategory category);

    @Mapping(target = "code", source = "supplierCode")
    @Mapping(target = "name", source = "supplierName")
    LowMarginReport.Supplier toResponse(LowMarginExcludedSupplier supplier);

    LowMarginReport.Supplier toResponse(Supplier supplier);

    /** {@code settings} pusty = sklep jeszcze nie ustawił minimalnej marży. */
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    PriceCheckSettingsResponse toResponse(PriceCheckSettings settings);
}
