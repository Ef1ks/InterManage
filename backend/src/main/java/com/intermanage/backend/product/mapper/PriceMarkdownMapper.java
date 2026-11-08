package com.intermanage.backend.product.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.product.dto.PriceMarkdownItem;
import com.intermanage.backend.product.entity.PriceMarkdown;

@Mapper
public interface PriceMarkdownMapper {

    PriceMarkdownItem toItem(PriceMarkdown markdown);
}
