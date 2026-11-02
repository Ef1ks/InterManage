package com.intermanage.backend.product.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.product.dto.ProductListItem;
import com.intermanage.backend.product.entity.Product;

@Mapper
public interface ProductCatalogMapper {

    @Mapping(target = "promoPrice", expression = "java(activePromoPrice(product, today))")
    ProductListItem toListItem(Product product, @Context LocalDate today);

    /** Cena promocyjna z kasy tylko w trakcie promocji. */
    default BigDecimal activePromoPrice(Product product, LocalDate today) {
        if (product.getPromoPrice() == null || product.getPromoFrom() == null || product.getPromoTo() == null) {
            return null;
        }
        return !today.isBefore(product.getPromoFrom()) && !today.isAfter(product.getPromoTo())
                ? product.getPromoPrice() : null;
    }
}
