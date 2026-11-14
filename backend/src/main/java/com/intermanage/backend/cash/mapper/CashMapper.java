package com.intermanage.backend.cash.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.cash.dto.CashClosingResponse;
import com.intermanage.backend.cash.dto.CashClosingSummary;
import com.intermanage.backend.cash.dto.CashDocumentResponse;
import com.intermanage.backend.cash.dto.CashSettings;
import com.intermanage.backend.cash.entity.CashDocument;
import com.intermanage.backend.cash.entity.CashOpeningBalance;
import com.intermanage.backend.cash.entity.CashReportClosing;
import com.intermanage.backend.cash.entity.CashReportClosingRow;
import com.intermanage.backend.cash.entity.CashStamp;

@Mapper
public interface CashMapper {

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    @Mapping(target = "cancelledBy", source = "cancelledBy.displayName")
    CashDocumentResponse toResponse(CashDocument document);

    @Mapping(target = "createdBy", source = "createdBy.displayName")
    CashSettings.Stamp toStamp(CashStamp stamp);

    @Mapping(target = "date", source = "balanceDate")
    @Mapping(target = "createdBy", source = "createdBy.displayName")
    CashSettings.OpeningBalance toOpeningBalance(CashOpeningBalance balance);

    @Mapping(target = "closedBy", source = "closedBy.displayName")
    CashClosingSummary toSummary(CashReportClosing closing);

    List<CashClosingSummary> toSummaries(List<CashReportClosing> closings);

    CashClosingResponse.Row toRow(CashReportClosingRow row);

    List<CashClosingResponse.Row> toRows(List<CashReportClosingRow> rows);
}
