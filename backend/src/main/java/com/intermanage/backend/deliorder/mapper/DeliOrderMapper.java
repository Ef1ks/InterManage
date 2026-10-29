package com.intermanage.backend.deliorder.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.deliorder.dto.DeliInvitationResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderDocumentResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderItemResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderSummaryResponse;
import com.intermanage.backend.deliorder.entity.DeliFormInvitation;
import com.intermanage.backend.deliorder.entity.DeliOrder;
import com.intermanage.backend.deliorder.entity.DeliOrderDocument;
import com.intermanage.backend.deliorder.entity.DeliOrderItem;
import com.intermanage.backend.store.mapper.StoreMapper;

@Mapper(uses = StoreMapper.class)
public interface DeliOrderMapper {

    DeliInvitationResponse toResponse(DeliFormInvitation invitation);

    @Mapping(target = "submittedBy", source = "submittedBy.displayName")
    @Mapping(target = "itemCount", expression = "java(order.getItems().size())")
    @Mapping(target = "corrected", expression = "java(order.getCorrectedAt() != null)")
    @Mapping(target = "merged", expression = "java(order.getMergedAt() != null)")
    DeliOrderSummaryResponse toSummary(DeliOrder order);

    @Mapping(target = "submittedBy", source = "submittedBy.displayName")
    @Mapping(target = "correctedBy", source = "correctedBy.displayName")
    @Mapping(target = "fulfilledBy", source = "fulfilledBy.displayName")
    @Mapping(target = "storeDeletedBy", source = "storeDeletedBy.displayName")
    @Mapping(target = "formStoreDeletedBy", source = "formStoreDeletedBy.displayName")
    @Mapping(target = "deliveryDateChangedBy", source = "deliveryDateChangedBy.displayName")
    @Mapping(target = "readBy", source = "readBy.displayName")
    @Mapping(target = "mergedBy", source = "mergedBy.displayName")
    @Mapping(target = "mergedIntoId", source = "mergedInto.id")
    DeliOrderResponse toResponse(DeliOrder order);

    @Mapping(target = "documentId", source = "document.id")
    @Mapping(target = "number", source = "document.number")
    @Mapping(target = "documentDate", source = "document.documentDate")
    @Mapping(target = "lineCount", source = "document.lineCount")
    @Mapping(target = "linkedBy", source = "linkedBy.displayName")
    DeliOrderDocumentResponse toResponse(DeliOrderDocument link);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "itm8", source = "product.itm8")
    @Mapping(target = "ean", source = "product.ean")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "brand", source = "product.brand")
    @Mapping(target = "addedBy", source = "addedBy.displayName")
    DeliOrderItemResponse toResponse(DeliOrderItem item);
}
