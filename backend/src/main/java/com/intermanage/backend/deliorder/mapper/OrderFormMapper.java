package com.intermanage.backend.deliorder.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.deliorder.dto.OrderCategoryResponse;
import com.intermanage.backend.deliorder.dto.OrderProductResponse;
import com.intermanage.backend.deliorder.dto.OrderSectionResponse;
import com.intermanage.backend.deliorder.entity.OrderCategory;
import com.intermanage.backend.deliorder.entity.OrderCategoryProduct;
import com.intermanage.backend.deliorder.entity.OrderSection;
import com.intermanage.backend.product.dto.ProductSearchResponse;
import com.intermanage.backend.product.entity.Product;

@Mapper
public interface OrderFormMapper {

    OrderSectionResponse toResponse(OrderSection section);

    OrderCategoryResponse toResponse(OrderCategory category);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "unit", source = "product.unit")
    @Mapping(target = "salePrice", source = "product.salePrice")
    @Mapping(target = "active", source = "product.active")
    OrderProductResponse toResponse(OrderCategoryProduct item);

    ProductSearchResponse toSearchResult(Product product);
}
