package com.intermanage.backend.deliproduction.mapper;

import java.math.BigDecimal;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.deliproduction.dto.ProductionArticleResponse;
import com.intermanage.backend.deliproduction.dto.ProductionCardResponse;
import com.intermanage.backend.deliproduction.dto.ProductionCardSummary;
import com.intermanage.backend.deliproduction.dto.ProductionSchemaResponse;
import com.intermanage.backend.deliproduction.entity.ProductionArticle;
import com.intermanage.backend.deliproduction.entity.ProductionCard;
import com.intermanage.backend.deliproduction.entity.ProductionCardLine;
import com.intermanage.backend.deliproduction.entity.ProductionSchema;
import com.intermanage.backend.store.entity.Store;

@Mapper
public interface ProductionMapper {

    @Mapping(target = "bakingTemperature", source = "schema.parameters.bakingTemperature")
    @Mapping(target = "internalTemperature", source = "schema.parameters.internalTemperature")
    @Mapping(target = "coolingTime", source = "schema.parameters.coolingTime")
    @Mapping(target = "coolingTemperature", source = "schema.parameters.coolingTemperature")
    @Mapping(target = "shelfLifeDays", source = "schema.parameters.shelfLifeDays")
    ProductionSchemaResponse toResponse(ProductionSchema schema, long articleCount);

    @Mapping(target = "schemaId", source = "schema.id")
    ProductionArticleResponse toResponse(ProductionArticle article);

    List<ProductionArticleResponse> toArticles(List<ProductionArticle> articles);

    @Mapping(target = "id", source = "card.id")
    @Mapping(target = "storeId", source = "card.storeId")
    @Mapping(target = "storeNumber", source = "store.number")
    @Mapping(target = "storeName", source = "store.name")
    @Mapping(target = "lines", expression = "java(toLines(card))")
    @Mapping(target = "createdBy", source = "card.createdBy.displayName")
    @Mapping(target = "createdAt", source = "card.createdAt")
    @Mapping(target = "updatedBy", source = "card.updatedBy.displayName")
    ProductionCardResponse toResponse(ProductionCard card, Store store, boolean editable);

    @Mapping(target = "id", source = "card.id")
    @Mapping(target = "storeId", source = "card.storeId")
    @Mapping(target = "storeNumber", source = "store.number")
    @Mapping(target = "storeName", source = "store.name")
    @Mapping(target = "createdBy", source = "card.createdBy.displayName")
    @Mapping(target = "createdAt", source = "card.createdAt")
    ProductionCardSummary toSummary(ProductionCard card, Store store, long lineCount, BigDecimal totalQuantity);

    default List<ProductionCardResponse.Line> toLines(ProductionCard card) {
        return card.getLines().stream().map(line -> toLine(line, card)).toList();
    }

    @Mapping(target = "id", source = "line.id")
    @Mapping(target = "articleId", source = "line.article.id")
    @Mapping(target = "code", source = "line.code")
    @Mapping(target = "name", source = "line.name")
    @Mapping(target = "bakingTemperature", source = "line.parameters.bakingTemperature")
    @Mapping(target = "internalTemperature", source = "line.parameters.internalTemperature")
    @Mapping(target = "coolingTime", source = "line.parameters.coolingTime")
    @Mapping(target = "coolingTemperature", source = "line.parameters.coolingTemperature")
    @Mapping(target = "shelfLifeDays", source = "line.parameters.shelfLifeDays")
    @Mapping(target = "expiryDate", expression = "java(line.expiryDate(card.getProductionDate()))")
    ProductionCardResponse.Line toLine(ProductionCardLine line, ProductionCard card);
}
