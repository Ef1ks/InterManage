package com.intermanage.backend.ai.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.ai.dto.AiUsageReport;
import com.intermanage.backend.ai.repository.AiUsageRepository;
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.component.StoreTime;

@Service
@Transactional(readOnly = true)
public class AiUsageService {

    private final AiUsageRepository usages;

    public AiUsageService(AiUsageRepository usages) {
        this.usages = usages;
    }

    /** Zużycie sklepu w dniach {@code from}..{@code to} (czas sklepu), najwyżej 366 dni. */
    public AiUsageReport report(UUID storeId, LocalDate from, LocalDate to) {
        ReportPeriod.check(from, to);
        List<AiUsageReport.Row> rows = usages.sumByModel(storeId, from.atStartOfDay(StoreTime.ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(StoreTime.ZONE).toInstant()).stream()
                .map(row -> new AiUsageReport.Row(row.getModel(), row.getFeature(), row.getRequests(),
                        row.getPromptTokens(), row.getOutputTokens(), row.getThoughtTokens(), row.getCachedTokens(),
                        row.getTotalTokens()))
                .toList();
        AiUsageReport.Totals total = new AiUsageReport.Totals(
                rows.stream().mapToLong(AiUsageReport.Row::requests).sum(),
                rows.stream().mapToLong(AiUsageReport.Row::promptTokens).sum(),
                rows.stream().mapToLong(AiUsageReport.Row::outputTokens).sum(),
                rows.stream().mapToLong(AiUsageReport.Row::thoughtTokens).sum(),
                rows.stream().mapToLong(AiUsageReport.Row::cachedTokens).sum(),
                rows.stream().mapToLong(AiUsageReport.Row::totalTokens).sum());
        return new AiUsageReport(from, to, rows, total);
    }
}
