package com.intermanage.backend.basedelivery.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.basedelivery.dto.BaseDeliveryLineResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliverySummary;
import com.intermanage.backend.basedelivery.dto.MeaOverview;
import com.intermanage.backend.basedelivery.entity.BaseDelivery;
import com.intermanage.backend.basedelivery.entity.BaseDeliveryLine;
import com.intermanage.backend.basedelivery.entity.MeaCode;
import com.intermanage.backend.basedelivery.entity.MeaConfirmation;
import com.intermanage.backend.dataentry.dto.CatalogProduct;

@Mapper
public interface BaseDeliveryMapper {

    @Mapping(target = "uploadedBy", source = "uploadedBy.displayName")
    BaseDeliverySummary toSummary(BaseDelivery delivery);

    @Mapping(target = "uploadedBy", source = "delivery.uploadedBy.displayName")
    @Mapping(target = "carriers", source = "carriers")
    @Mapping(target = "lines", source = "lines")
    BaseDeliveryResponse toResponse(BaseDelivery delivery, List<BaseDeliveryResponse.Carrier> carriers,
            List<BaseDeliveryLineResponse> lines);

    /** Linia z artykułem z kartoteki; pola wspólne z artykułem (kody, nazwa, cena zakupu) zawsze z pliku. */
    @Mapping(target = "id", source = "line.id")
    @Mapping(target = "itm8", source = "line.itm8")
    @Mapping(target = "ean", source = "line.ean")
    @Mapping(target = "name", source = "line.name")
    @Mapping(target = "brand", source = "line.brand")
    @Mapping(target = "purchasePrice", source = "line.purchasePrice")
    @Mapping(target = "subfamilyCode", source = "line.subfamilyCode")
    @Mapping(target = "product", source = "product")
    @Mapping(target = "catalogDeposit", source = "catalogDeposit")
    @Mapping(target = "supplierCode", expression = "java(com.intermanage.backend.basedelivery.component.BaseSupplier.of(line, product))")
    BaseDeliveryLineResponse toLine(BaseDeliveryLine line, CatalogProduct product, Short catalogDeposit);

    @Mapping(target = "confirmedBy", source = "confirmedBy.displayName")
    @Mapping(target = "deletedBy", source = "deletedBy.displayName")
    MeaOverview.Confirmation toConfirmation(MeaConfirmation confirmation);

    default String code(MeaCode code) {
        return code.getCode();
    }
}
