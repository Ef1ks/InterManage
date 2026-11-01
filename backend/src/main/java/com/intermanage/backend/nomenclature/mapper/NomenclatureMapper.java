package com.intermanage.backend.nomenclature.mapper;

import java.math.BigDecimal;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.nomenclature.dto.NomenclatureImportResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureImport;
import com.intermanage.backend.nomenclature.entity.NomenclatureMargin;

@Mapper
public interface NomenclatureMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    NomenclatureImportResponse toResponse(NomenclatureImport nomenclatureImport);

    NomenclatureOption toOption(Nomenclature nomenclature);

    /** Pozycja nomenklatury z marżą wymaganą; {@code margin} pusty = nic na niej nie ustawiono. */
    @Mapping(target = "code", source = "nomenclature.code")
    @Mapping(target = "name", source = "nomenclature.name")
    @Mapping(target = "level", source = "nomenclature.level")
    @Mapping(target = "marginPercent", source = "margin.marginPercent")
    @Mapping(target = "effectivePercent", source = "effectivePercent")
    @Mapping(target = "updatedBy", source = "margin.updatedBy.displayName")
    @Mapping(target = "updatedAt", source = "margin.updatedAt")
    NomenclatureMarginResponse toMarginResponse(Nomenclature nomenclature, NomenclatureMargin margin,
            BigDecimal effectivePercent);
}
