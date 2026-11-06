package com.intermanage.backend.bakery.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.bakery.dto.BakeryArticle;
import com.intermanage.backend.bakery.dto.BakeryWzResponse;
import com.intermanage.backend.bakery.entity.BakeryWz;
import com.intermanage.backend.bakery.entity.BakeryWzLine;
import com.intermanage.backend.product.entity.Product;

@Mapper
public interface BakeryMapper {

    @Mapping(target = "productId", source = "id")
    BakeryArticle toArticle(Product product);

    @Mapping(target = "article", source = "product")
    BakeryWzResponse.Line toResponse(BakeryWzLine line);

    @Mapping(target = "sourceInvoice", source = "sourceInvoice.invoiceNumber")
    @Mapping(target = "scanId", source = "scan.id")
    @Mapping(target = "scanFileName", source = "scan.fileName")
    @Mapping(target = "scanContentType", source = "scan.contentType")
    BakeryWzResponse toResponse(BakeryWz wz);
}
