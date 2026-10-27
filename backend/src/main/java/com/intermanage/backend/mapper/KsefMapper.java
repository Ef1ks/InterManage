package com.intermanage.backend.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.KsefConnectionResponse;
import com.intermanage.backend.dto.KsefInternalIdResponse;
import com.intermanage.backend.dto.KsefInvoiceSummary;
import com.intermanage.backend.dto.KsefSkippedSupplierResponse;
import com.intermanage.backend.entity.KsefConnection;
import com.intermanage.backend.entity.KsefInternalId;
import com.intermanage.backend.entity.KsefInvoice;
import com.intermanage.backend.entity.KsefInvoiceEntry;
import com.intermanage.backend.entity.KsefSkippedSupplier;

@Mapper
public interface KsefMapper {

    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    KsefConnectionResponse toResponse(KsefConnection connection);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    KsefInternalIdResponse toResponse(KsefInternalId internalId);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    KsefSkippedSupplierResponse toResponse(KsefSkippedSupplier supplier);

    /**
     * {@code deliveries}: przyjęcia PZ sklepu z numerem faktury i dokumenty przypięte ręcznie, {@code entry}: ręczne
     * oznaczenie w sklepie albo {@code null}; razem dają "wprowadzona".
     */
    @Mapping(target = "id", source = "invoice.id")
    @Mapping(target = "internalIds", expression = "java(internalIds(invoice))")
    @Mapping(target = "entered", expression = "java(entry != null || !deliveries.isEmpty())")
    @Mapping(target = "enteredBy", source = "entry.enteredBy.displayName")
    @Mapping(target = "enteredAt", source = "entry.enteredAt")
    @Mapping(target = "deliveries", source = "deliveries")
    KsefInvoiceSummary toSummary(KsefInvoice invoice, KsefInvoiceEntry entry,
            List<KsefInvoiceSummary.Delivery> deliveries, boolean supplierSkipped);

    default List<String> internalIds(KsefInvoice invoice) {
        return invoice.getInternalIds().stream().map(KsefInvoice.InternalIdRef::internalId).sorted().toList();
    }
}
