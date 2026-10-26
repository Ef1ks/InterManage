package com.intermanage.backend.mapper;

import java.math.BigDecimal;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.FinishedProductResponse;
import com.intermanage.backend.dto.PriceCalculationResponse;
import com.intermanage.backend.dto.PriceCalculationSummary;
import com.intermanage.backend.dto.PriceChangeResponse;
import com.intermanage.backend.dto.PriceProductResponse;
import com.intermanage.backend.entity.IngredientPrice;
import com.intermanage.backend.entity.PriceCalculation;
import com.intermanage.backend.entity.PriceCalculationItem;
import com.intermanage.backend.entity.Product;

@Mapper
public interface PriceCalculationMapper {

    @Mapping(target = "product", expression = "java(finished(calculation))")
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    @Mapping(target = "manualPriceSetBy", source = "manualPriceSetBy.displayName")
    PriceCalculationSummary toSummary(PriceCalculation calculation);

    @Mapping(target = "product", expression = "java(finished(calculation))")
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    @Mapping(target = "manualPriceSetBy", source = "manualPriceSetBy.displayName")
    @Mapping(target = "markupPercent", constant = "" + PriceCalculation.MARKUP_PERCENT)
    PriceCalculationResponse toResponse(PriceCalculation calculation);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "unit", source = "product.costUnit")
    @Mapping(target = "pieceWeight", source = "product.pieceWeight")
    @Mapping(target = "active", source = "product.active")
    @Mapping(target = "priceDocumentId", source = "priceDocument.id")
    @Mapping(target = "priceDocumentNumber", source = "priceDocument.number")
    @Mapping(target = "priceDate", source = "priceDocument.documentDate")
    PriceProductResponse toResponse(PriceCalculationItem item);

    /** Wynik wyszukiwania składnika z aktualną ceną (ostatnia dostawa albo kartoteka). */
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "unit", source = "product.costUnit")
    @Mapping(target = "pieceWeight", source = "product.pieceWeight")
    @Mapping(target = "active", source = "product.active")
    @Mapping(target = "unitCost", source = "price.unitCost")
    @Mapping(target = "priceSource", source = "price.source")
    @Mapping(target = "priceDocumentId", source = "price.document.id")
    @Mapping(target = "priceDocumentNumber", source = "price.document.number")
    @Mapping(target = "priceDate", source = "price.document.documentDate")
    @Mapping(target = "quantity", ignore = true)
    @Mapping(target = "cost", ignore = true)
    @Mapping(target = "pricedAt", ignore = true)
    PriceProductResponse toSearchResult(Product product, IngredientPrice price);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "unit", source = "product.costUnit")
    FinishedProductResponse toFinished(Product product, UUID calculationId);

    /** Podgląd "Odśwież ceny": cena teraz i wyliczona przed odświeżeniem z kalkulacji, nowa z podglądu. */
    @Mapping(target = "calculationId", source = "calculation.id")
    @Mapping(target = "name", source = "calculation.name")
    @Mapping(target = "ean", source = "calculation.product.ean")
    @Mapping(target = "currentPrice", source = "calculation.salePrice")
    @Mapping(target = "manualPrice", source = "calculation.manualPrice")
    @Mapping(target = "previousCalculatedPrice", source = "calculation.calculatedPrice")
    PriceChangeResponse toChange(PriceCalculation calculation, BigDecimal newCalculatedPrice, BigDecimal changePercent,
            boolean needsDecision);

    /** Wyrób kalkulacji; pusty w kalkulacjach sprzed powiązania z kartoteką. */
    default FinishedProductResponse finished(PriceCalculation calculation) {
        return calculation.getProduct() == null ? null : toFinished(calculation.getProduct(), calculation.getId());
    }
}
