package com.intermanage.backend.meat.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.meat.dto.MeatArticle;
import com.intermanage.backend.meat.dto.MeatDeliveryResponse;
import com.intermanage.backend.meat.dto.MeatDeliverySummary;
import com.intermanage.backend.meat.dto.MeatSupplier;
import com.intermanage.backend.meat.entity.MeatDelivery;
import com.intermanage.backend.meat.entity.MeatDeliveryLine;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.supplier.entity.Supplier;

@Mapper
public interface MeatMapper {

    MeatArticle toArticle(Product product);

    MeatSupplier toSupplier(Supplier supplier);

    @Mapping(target = "article", source = "product")
    MeatDeliveryResponse.Line toResponse(MeatDeliveryLine line);

    @Mapping(target = "scanId", source = "scan.id")
    @Mapping(target = "scanContentType", source = "scan.contentType")
    @Mapping(target = "createdBy", source = "createdBy.displayName")
    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    MeatDeliveryResponse toResponse(MeatDelivery delivery);

    @Mapping(target = "hasScan", expression = "java(delivery.getScan() != null)")
    @Mapping(target = "createdBy", source = "delivery.createdBy.displayName")
    MeatDeliverySummary toSummary(MeatDelivery delivery, int lineCount);

    List<MeatArticle> toArticles(List<Product> products);
}
