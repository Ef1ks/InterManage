package com.intermanage.backend.importguide.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.importguide.dto.ImportCoverage;
import com.intermanage.backend.importguide.dto.ImportHistoryPage;
import com.intermanage.backend.importguide.service.ImportCoverageService;
import com.intermanage.backend.importguide.service.ImportHistoryService;

/** Wspólna historia importów na dole Importu danych, doczytywana porcjami przy przewijaniu. */
@RestController
public class ImportHistoryController {

    private final ImportHistoryService service;
    private final ImportCoverageService coverage;

    public ImportHistoryController(ImportHistoryService service, ImportCoverageService coverage) {
        this.service = service;
        this.coverage = coverage;
    }

    /** Dni objęte wgranymi raportami sprzedaży i dokumentów: makro z Mercalys drukuje to, czego brakuje. */
    @GetMapping("/api/stores/{storeId}/imports/coverage")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ImportCoverage coverage(@PathVariable UUID storeId) {
        return coverage.coverage(storeId);
    }

    /** Porcja wgrań starszych niż {@code before} (chwila wgrania ostatniej pozycji poprzedniej porcji). */
    @GetMapping("/api/stores/{storeId}/imports/history")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ImportHistoryPage history(@PathVariable UUID storeId, @RequestParam(required = false) Instant before) {
        return service.page(storeId, before);
    }
}
