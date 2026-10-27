package com.intermanage.backend.ksef.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.ksef.dto.KsefConnectionResponse;
import com.intermanage.backend.ksef.dto.KsefInternalIdResponse;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.dto.KsefSkippedSupplierResponse;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInternalId;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceEntry;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;

@Mapper
public interface KsefMapper {

    @Mapping(target = "updatedBy", source = "updatedBy.displayName")
    KsefConnectionResponse toResponse(KsefConnection connection);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    KsefInternalIdResponse toResponse(KsefInternalId internalId);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    KsefSkippedSupplierResponse toResponse(KsefSkippedSupplier supplier);

    /**
     * {@code deliveries}: przyjęcia PZ sklepów firmy z numerem faktury i dokumenty przypięte ręcznie, {@code entry}:
     * ręczne oznaczenie w sklepie firmy (najpierw w bieżącym) albo {@code null}; razem dają "wprowadzona".
     * {@code enteredStore}: sklep, w którym oznaczono ręcznie ("10023 · Kisielińska").
     */
    @Mapping(target = "id", source = "invoice.id")
    @Mapping(target = "internalIds", expression = "java(internalIds(invoice))")
    @Mapping(target = "entered", expression = "java(entry != null || !deliveries.isEmpty())")
    @Mapping(target = "enteredBy", source = "entry.enteredBy.displayName")
    @Mapping(target = "enteredAt", source = "entry.enteredAt")
    @Mapping(target = "enteredStoreId", source = "entry.id.storeId")
    @Mapping(target = "enteredStore", source = "enteredStore")
    @Mapping(target = "deliveries", source = "deliveries")
    KsefInvoiceSummary toSummary(KsefInvoice invoice, KsefInvoiceEntry entry,
            List<KsefInvoiceSummary.Delivery> deliveries, boolean supplierSkipped, String enteredStore);

    default List<String> internalIds(KsefInvoice invoice) {
        return invoice.getInternalIds().stream().map(KsefInvoice.InternalIdRef::internalId).sorted().toList();
    }
}
