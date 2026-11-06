package com.intermanage.backend.sales.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.sales.dto.ArticleDeliveryResponse;
import com.intermanage.backend.sales.dto.ArticleProtocolResponse;
import com.intermanage.backend.sales.dto.RegionBudgetResponse;
import com.intermanage.backend.sales.dto.SalesArticleResponse;
import com.intermanage.backend.sales.dto.SalesImportResponse;
import com.intermanage.backend.sales.dto.ZeroStockReport;
import com.intermanage.backend.sales.entity.RegionBudget;
import com.intermanage.backend.sales.entity.SalesImport;

@Mapper
public interface SalesMapper {

    /** {@code importedBy} jest pusty, gdy konto osoby importującej zostało usunięte. */
    @Mapping(target = "importedBy", source = "importedBy.displayName")
    SalesImportResponse toResponse(SalesImport salesImport);

    @Mapping(target = "reportGeneratedAt", source = "product.lastSeenImport.reportGeneratedAt")
    @Mapping(target = "nomenclaturePath", source = "path")
    SalesArticleResponse toArticle(Product product, List<NomenclatureOption> path, BigDecimal vatPercent);

    ZeroStockReport.Item toZeroStockItem(Product product);

    @Mapping(target = "purchasePrice", expression = "java(unitPrice(delivery))")
    ArticleDeliveryResponse toDelivery(DocumentLineRepository.ArticleDelivery delivery);

    @Mapping(target = "label", expression = "java(protocolLabel(protocol))")
    ArticleProtocolResponse toProtocol(DocumentLineRepository.ArticleProtocol protocol);

    /** Przeniesienie stanów zarejestrowane jako gratis to w statystykach gratis, reszta ma nazwę typu dokumentu. */
    default String protocolLabel(DocumentLineRepository.ArticleProtocol protocol) {
        return protocol.getGratis() ? "Gratis" : DocumentType.valueOf(protocol.getDocumentType()).singular();
    }

    /** Cena zakupu netto sztuki (kg) z sumy linii przyjęcia, do 4 miejsc jak w raporcie. */
    default BigDecimal unitPrice(DocumentLineRepository.ArticleDelivery delivery) {
        BigDecimal quantity = delivery.getQuantity();
        return quantity == null || quantity.signum() == 0 ? null
                : delivery.getPurchaseValue().divide(quantity, 4, RoundingMode.HALF_UP);
    }

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
