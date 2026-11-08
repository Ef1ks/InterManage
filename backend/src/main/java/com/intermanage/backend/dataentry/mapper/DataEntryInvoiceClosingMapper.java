package com.intermanage.backend.dataentry.mapper;

import org.mapstruct.Mapper;

import com.intermanage.backend.dataentry.dto.InvoiceClosingItem;
import com.intermanage.backend.dataentry.entity.DataEntryInvoiceClosing;

@Mapper
public interface DataEntryInvoiceClosingMapper {

    InvoiceClosingItem toItem(DataEntryInvoiceClosing closing);
}
