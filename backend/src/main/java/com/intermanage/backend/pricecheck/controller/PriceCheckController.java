package com.intermanage.backend.pricecheck.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.pricecheck.dto.CadencierImportResponse;
import com.intermanage.backend.pricecheck.dto.CadencierOverview;
import com.intermanage.backend.pricecheck.dto.IncidentImportResponse;
import com.intermanage.backend.pricecheck.dto.IncidentOverview;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryResponse;
import com.intermanage.backend.pricecheck.dto.PriceCheckReport;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckReviewResponse;
import com.intermanage.backend.pricecheck.dto.PriceCheckSettingsRequest;
import com.intermanage.backend.pricecheck.dto.PriceCheckSettingsResponse;
import com.intermanage.backend.pricecheck.service.CadencierService;
import com.intermanage.backend.pricecheck.service.IncidentService;
import com.intermanage.backend.pricecheck.service.PriceCheckCategoryService;
import com.intermanage.backend.pricecheck.service.PriceCheckService;

import jakarta.validation.Valid;

/**
 * Raport zakupu bazowego poza bazą (kafelek w Sprzedaży i raportach), statusy wyjaśniania przyjęć z dostawcą,
 * a w Ustawieniach sklepu minimalna marża i lista artykułów obecnych w cadencier.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/price-check")
public class PriceCheckController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceCheckService priceCheckService;
    private final CadencierService cadencierService;
    private final IncidentService incidentService;
    private final PriceCheckCategoryService categoryService;

    public PriceCheckController(PriceCheckService priceCheckService, CadencierService cadencierService,
            IncidentService incidentService, PriceCheckCategoryService categoryService) {
        this.priceCheckService = priceCheckService;
        this.cadencierService = cadencierService;
        this.incidentService = incidentService;
        this.categoryService = categoryService;
    }

    /** Przyjęcia z ostatnich {@value PriceCheckService#PERIOD_DAYS} dni. */
    @GetMapping
    @PreAuthorize(VIEW)
    public PriceCheckReport report(@PathVariable UUID storeId) {
        return priceCheckService.report(storeId);
    }

    /** Ile artykułów jest w cadencier i ostatnie wgrania listy. */
    @GetMapping("/cadencier")
    @PreAuthorize(VIEW)
    public CadencierOverview cadencier(@PathVariable UUID storeId) {
        return cadencierService.overview(storeId);
    }

    /** Lista "OBECNE W CADENCIER" (.xlsx) wgrywana w Ustawieniach sklepu. */
    @PostMapping(path = "/cadencier", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public CadencierImportResponse importCadencier(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return cadencierService.importReport(storeId, file, principal.getId());
    }

    /** Najnowszy dzień z incydentami i ostatnie wgrania. */
    @GetMapping("/incidents")
    @PreAuthorize(VIEW)
    public IncidentOverview incidents(@PathVariable UUID storeId) {
        return incidentService.overview(storeId);
    }

    /** Plik incydentów zamówień do bazy (.xlsx), dzień z nazwy pliku ({@code <nr sklepu>_RRMMDD_...}). */
    @PostMapping(path = "/incidents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public IncidentImportResponse importIncidents(@PathVariable UUID storeId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        return incidentService.importReport(storeId, file, principal.getId());
    }

    /** Własne kategorie raportu (rejony nomenklatury), w kolejności dodania. */
    @GetMapping("/categories")
    @PreAuthorize(VIEW)
    public List<PriceCheckCategoryResponse> categories(@PathVariable UUID storeId) {
        return categoryService.list(storeId);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public PriceCheckCategoryResponse createCategory(@PathVariable UUID storeId,
            @Valid @RequestBody PriceCheckCategoryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return categoryService.create(storeId, request, principal.getId());
    }

    @PutMapping("/categories/{categoryId}")
    @PreAuthorize(EDIT)
    public PriceCheckCategoryResponse updateCategory(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @Valid @RequestBody PriceCheckCategoryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return categoryService.update(storeId, categoryId, request, principal.getId());
    }

    /** Usunięcie kategorii (znacznik). */
    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void deleteCategory(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        categoryService.delete(storeId, categoryId, principal.getId());
    }

    @PostMapping("/categories/{categoryId}/restore")
    @PreAuthorize(EDIT)
    public PriceCheckCategoryResponse restoreCategory(@PathVariable UUID storeId, @PathVariable UUID categoryId) {
        return categoryService.restore(storeId, categoryId);
    }

    @GetMapping("/settings")
    @PreAuthorize(VIEW)
    public PriceCheckSettingsResponse settings(@PathVariable UUID storeId) {
        return priceCheckService.settings(storeId);
    }

    @PutMapping("/settings")
    @PreAuthorize(EDIT)
    public PriceCheckSettingsResponse saveSettings(@PathVariable UUID storeId,
            @Valid @RequestBody PriceCheckSettingsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceCheckService.saveSettings(storeId, request, principal.getId());
    }

    /** Nowy status wyjaśniania przyjęcia; zwraca historię zmian od najnowszej. */
    @PostMapping("/documents/{documentId}/reviews")
    @PreAuthorize(EDIT)
    public List<PriceCheckReviewResponse> review(@PathVariable UUID storeId, @PathVariable UUID documentId,
            @Valid @RequestBody PriceCheckReviewRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceCheckService.review(storeId, documentId, request, principal.getId());
    }

    /** Cofnięcie zmiany statusu (znacznik). */
    @DeleteMapping("/reviews/{reviewId}")
    @PreAuthorize(EDIT)
    public List<PriceCheckReviewResponse> removeReview(@PathVariable UUID storeId, @PathVariable UUID reviewId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceCheckService.removeReview(storeId, reviewId, principal.getId());
    }

    @PostMapping("/reviews/{reviewId}/restore")
    @PreAuthorize(EDIT)
    public List<PriceCheckReviewResponse> restoreReview(@PathVariable UUID storeId, @PathVariable UUID reviewId) {
        return priceCheckService.restoreReview(storeId, reviewId);
    }
}
