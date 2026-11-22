package com.intermanage.backend.importguide.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.importguide.component.ReportSaveMacro;
import com.intermanage.backend.importguide.dto.ReportMacroResponse;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.store.service.StoreFileLocationService;

/**
 * Makro zapisu raportów z Mercalys dla sklepu: ustawienia importu (ścieżki sklepu) i okresy wgranych raportów, z dziś
 * czasu sklepu. To samo dostaje przeglądarka („Zaktualizuj wszystkie pliki”) i agent (aktualizacja przy starcie).
 */
@Service
public class ReportMacroService {

    private final StoreFileLocationService locations;
    private final ImportCoverageService coverage;

    public ReportMacroService(StoreFileLocationService locations, ImportCoverageService coverage) {
        this.locations = locations;
        this.coverage = coverage;
    }

    /** Pusta lista raportów = wszystkie raporty z makrem. */
    public ReportMacroResponse build(UUID storeId, List<ImportReportKind> kinds) {
        List<ImportReportKind> wanted = kinds == null || kinds.isEmpty() ? ReportSaveMacro.MACRO_REPORTS : kinds;
        ReportSaveMacro.Result result = ReportSaveMacro.build(wanted, locations.get(storeId).locations(),
                coverage.coverage(storeId), LocalDate.now(StoreTime.ZONE));
        return new ReportMacroResponse(result.macro(), ReportSaveMacro.OPEN_LINE,
                result.reports().stream().map(Enum::name).toList(),
                result.skipped().stream().map(skipped -> new ReportMacroResponse.Skipped(skipped.kind().name(),
                        ReportSaveMacro.title(skipped.kind()), skipped.reason())).toList(),
                result.files().stream().map(file -> new ReportMacroResponse.SavedFile(file.kind().name(),
                        ReportSaveMacro.title(file.kind()), file.fileName())).toList(),
                result.playerVersion());
    }
}
