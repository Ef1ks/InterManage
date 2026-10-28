package com.intermanage.backend.sales.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.sales.dto.RegionBudgetResponse;
import com.intermanage.backend.sales.dto.SalesArticleResponse;
import com.intermanage.backend.sales.dto.SalesImportResponse;
import com.intermanage.backend.sales.entity.RegionBudget;
import com.intermanage.backend.sales.entity.SalesImport;

@Mapper
public interface SalesMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SalesImportResponse toResponse(SalesImport salesImport);

    @Mapping(target = "reportGeneratedAt", source = "product.lastSeenImport.reportGeneratedAt")
    @Mapping(target = "nomenclaturePath", source = "path")
    SalesArticleResponse toArticle(Product product, List<NomenclatureOption> path);

    /** Budżet rejonu; {@code budget} pusty = rejon bez ustawionego budżetu. */
    @Mapping(target = "regionCode", source = "regionCode")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "group", source = "group")
    @Mapping(target = "marginPercent", source = "budget.marginPercent")
    @Mapping(target = "maxLossPercent", source = "budget.maxLossPercent")
    @Mapping(target = "updatedBy", source = "budget.updatedBy.displayName")
    @Mapping(target = "updatedAt", source = "budget.updatedAt")
    RegionBudgetResponse toBudget(String regionCode, String name, String group, RegionBudget budget);
}
