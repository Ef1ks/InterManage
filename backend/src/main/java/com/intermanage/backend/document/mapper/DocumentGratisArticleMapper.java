package com.intermanage.backend.document.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.document.dto.GratisArticleItem;
import com.intermanage.backend.document.entity.DocumentGratisArticle;

@Mapper
public interface DocumentGratisArticleMapper {

    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "addedBy", source = "addedBy.displayName")
    GratisArticleItem toItem(DocumentGratisArticle article);
}
