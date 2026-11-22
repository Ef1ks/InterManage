package com.intermanage.backend.importguide.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.importguide.dto.ImportCoverage;
import com.intermanage.backend.importguide.dto.ImportHistoryPage;
import com.intermanage.backend.importguide.dto.ReportMacroResponse;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.importguide.service.ImportCoverageService;
import com.intermanage.backend.importguide.service.ImportHistoryService;
import com.intermanage.backend.importguide.service.ReportMacroService;

/** Wspólna historia importów na dole Importu danych, doczytywana porcjami przy przewijaniu. */
@RestController
public class ImportHistoryController {

    private final ImportHistoryService service;
    private final ImportCoverageService coverage;
    private final ReportMacroService macros;

    public ImportHistoryController(ImportHistoryService service, ImportCoverageService coverage,
            ReportMacroService macros) {
        this.macros = macros;
        this.service = service;
        this.coverage = coverage;
    }

    /** Dni objęte wgranymi raportami sprzedaży i dokumentów: makro z Mercalys drukuje to, czego brakuje. */
    @GetMapping("/api/stores/{storeId}/imports/coverage")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ImportCoverage coverage(@PathVariable UUID storeId) {
        return coverage.coverage(storeId);
    }

    /**
     * Makro zapisu raportów z Mercalys do folderów importu (przycisk „Zaktualizuj wszystkie pliki” i „Zaktualizuj
     * plik” przy raporcie); bez {@code kinds} wszystkie raporty z makrem.
     */
    @GetMapping("/api/stores/{storeId}/imports/report-macro")
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ReportMacroResponse reportMacro(@PathVariable UUID storeId,
            @RequestParam(required = false) List<ImportReportKind> kinds) {
        return macros.build(storeId, kinds);
    }

    /** Porcja wgrań starszych niż {@code before} (chwila wgrania ostatniej pozycji poprzedniej porcji). */
    @GetMapping("/api/stores/{storeId}/imports/history")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ImportHistoryPage history(@PathVariable UUID storeId, @RequestParam(required = false) Instant before) {
        return service.page(storeId, before);
    }
}
