package com.intermanage.backend.leaflet.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.leaflet.dto.LeafletLineResponse;
import com.intermanage.backend.leaflet.dto.LeafletResponse;
import com.intermanage.backend.leaflet.dto.LeafletSummary;
import com.intermanage.backend.leaflet.entity.PromoLeaflet;
import com.intermanage.backend.leaflet.entity.PromoLeafletLine;

@Mapper
public interface LeafletMapper {

    @Mapping(target = "uploadedBy", source = "uploadedBy.displayName")
    LeafletSummary toSummary(PromoLeaflet leaflet);

    @Mapping(target = "uploadedBy", source = "leaflet.uploadedBy.displayName")
    @Mapping(target = "lines", source = "lines")
    @Mapping(target = "pendingMeaCodes", source = "pendingMeaCodes")
    LeafletResponse toResponse(PromoLeaflet leaflet, List<LeafletLineResponse> lines, List<String> pendingMeaCodes);

    /** Pozycja z artykułem z kartoteki; kody, nazwa i marka zawsze z gazetki. */
    @Mapping(target = "id", source = "line.id")
    @Mapping(target = "itm8", source = "line.itm8")
    @Mapping(target = "ean", source = "line.ean")
    @Mapping(target = "name", source = "line.name")
    @Mapping(target = "brand", source = "line.brand")
    @Mapping(target = "product", source = "product")
    @Mapping(target = "regularPrice", source = "regularPrice")
    @Mapping(target = "linkId", source = "linkId")
    LeafletLineResponse toLine(PromoLeafletLine line, CatalogProduct product, BigDecimal regularPrice, UUID linkId);
}
