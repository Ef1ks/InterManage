package com.intermanage.backend.deliprice.mapper;

import java.math.BigDecimal;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.deliprice.dto.FinishedProductResponse;
import com.intermanage.backend.deliprice.dto.PartnerCalculation;
import com.intermanage.backend.deliprice.dto.PartnerStore;
import com.intermanage.backend.deliprice.dto.PriceCalculationResponse;
import com.intermanage.backend.deliprice.dto.PurchaseFileLine;
import com.intermanage.backend.deliprice.dto.PriceCalculationSummary;
import com.intermanage.backend.deliprice.dto.PriceChangeResponse;
import com.intermanage.backend.deliprice.dto.PriceProductResponse;
import com.intermanage.backend.deliprice.entity.IngredientPrice;
import com.intermanage.backend.deliprice.entity.PriceCalculation;
import com.intermanage.backend.deliprice.entity.PriceCalculationItem;
import com.intermanage.backend.deliprice.entity.PriceCalculationLink;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.store.entity.Store;

@Mapper
public interface PriceCalculationMapper {

    @Mapping(target = "product", expression = "java(finished(calculation))")
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    @Mapping(target = "manualPriceSetBy", source = "manualPriceSetBy.displayName")
    PriceCalculationSummary toSummary(PriceCalculation calculation);

    @Mapping(target = "product", expression = "java(finished(calculation))")
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    @Mapping(target = "manualPriceSetBy", source = "manualPriceSetBy.displayName")
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

    PartnerStore toPartnerStore(Store store);

    /** Pozycja pliku CADBEZP: {@code purchasePrice} z kosztu kalkulacji, obecna cena z kartoteki. */
    @Mapping(target = "calculationId", source = "calculation.id")
    @Mapping(target = "name", source = "calculation.name")
    @Mapping(target = "ean", source = "calculation.product.ean")
    @Mapping(target = "supplier", source = "calculation.product.supplier")
    @Mapping(target = "currentPrice", source = "calculation.product.purchasePrice")
    @Mapping(target = "missingPriceCount", source = "calculation.missingPriceCount")
    @Mapping(target = "countedInPieces", source = "calculation.product.countedInPieces")
    PurchaseFileLine toPurchaseLine(PriceCalculation calculation, java.math.BigDecimal purchasePrice);

    /** Kalkulacja sąsiada z przypisanym artykułem sklepu ({@code link} pusty, gdy nieprzypisana). */
    @Mapping(target = "calculationId", source = "calculation.id")
    @Mapping(target = "sourceProductId", source = "calculation.product.id")
    @Mapping(target = "name", source = "calculation.name")
    @Mapping(target = "ean", source = "calculation.product.ean")
    @Mapping(target = "salePrice", source = "calculation.salePrice")
    @Mapping(target = "vatRate", source = "calculation.vatRate")
    @Mapping(target = "withoutRecipe", expression = "java(calculation.getItemCount() == 0)")
    @Mapping(target = "link", source = "link")
    PartnerCalculation toPartner(PriceCalculation calculation, PriceCalculationLink link);

    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "salePrice", source = "product.salePrice")
    PartnerCalculation.Link toLink(PriceCalculationLink link);

    /** Wyrób kalkulacji; pusty w kalkulacjach sprzed powiązania z kartoteką. */
    default FinishedProductResponse finished(PriceCalculation calculation) {
        return calculation.getProduct() == null ? null : toFinished(calculation.getProduct(), calculation.getId());
    }
}
