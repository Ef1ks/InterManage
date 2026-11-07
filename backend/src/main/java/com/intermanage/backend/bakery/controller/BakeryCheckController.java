package com.intermanage.backend.bakery.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.intermanage.backend.bakery.dto.BakeryArticle;
import com.intermanage.backend.bakery.dto.BakeryBon;
import com.intermanage.backend.bakery.dto.BakeryCheckRequest;
import com.intermanage.backend.bakery.dto.BakeryCheckResponse;
import com.intermanage.backend.bakery.dto.BakeryCheckSummary;
import com.intermanage.backend.bakery.dto.BakeryComparison;
import com.intermanage.backend.bakery.dto.BakeryHiddenArticlesRequest;
import com.intermanage.backend.bakery.dto.BakeryInvoiceCandidate;
import com.intermanage.backend.bakery.dto.BakeryLinkRequest;
import com.intermanage.backend.bakery.dto.BakeryScanReading;
import com.intermanage.backend.bakery.dto.BakerySupplierArticles;
import com.intermanage.backend.bakery.dto.BakeryWzRequest;
import com.intermanage.backend.bakery.dto.BakeryWzResponse;
import com.intermanage.backend.bakery.service.BakeryCheckService;
import com.intermanage.backend.bakery.service.BakeryComparisonService;
import com.intermanage.backend.bakery.service.BakerySupplierArticleService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Weryfikacja piekarni. Uprawnienie jak wprowadzanie danych (te same faktury i przypięcia kodów), a że pokazuje bony
 * i faktury, wymaga też ich odczytu. Usunięcie weryfikacji i WZ oraz odpięcie to znaczniki.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/bakery-checks")
public class BakeryCheckController {

    private static final String ACCESS = "hasAuthority('DATA_ENTRY:VIEW') and hasAuthority('DOCUMENTS:VIEW')"
            + " and hasAuthority('KSEF_INVOICES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final BakeryCheckService service;
    private final BakeryComparisonService comparison;
    private final BakerySupplierArticleService supplierArticles;

    public BakeryCheckController(BakeryCheckService service, BakeryComparisonService comparison,
            BakerySupplierArticleService supplierArticles) {
        this.service = service;
        this.comparison = comparison;
        this.supplierArticles = supplierArticles;
    }

    @GetMapping("/deliveries")
    @PreAuthorize(ACCESS)
    public List<BakeryBon> deliveries(@PathVariable UUID storeId, @RequestParam UUID supplierId) {
        return service.deliveries(storeId, supplierId);
    }

    /** Artykuły dostawcy z zaznaczeniem, które są domyślnie na WZ. */
    @GetMapping("/suppliers/{supplierId}/articles")
    @PreAuthorize(ACCESS)
    public BakerySupplierArticles supplierArticles(@PathVariable UUID storeId, @PathVariable UUID supplierId) {
        return supplierArticles.get(storeId, supplierId);
    }

    @PutMapping("/suppliers/{supplierId}/articles")
    @PreAuthorize(ACCESS)
    public BakerySupplierArticles updateSupplierArticles(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @Valid @RequestBody BakeryHiddenArticlesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return supplierArticles.update(storeId, supplierId, request.hiddenProductIds(), principal.getId());
    }

    @GetMapping("/articles/{itm8}")
    @PreAuthorize(ACCESS)
    public BakeryArticle article(@PathVariable UUID storeId, @PathVariable String itm8) {
        return service.article(storeId, itm8);
    }

    @GetMapping
    @PreAuthorize(ACCESS)
    public List<BakeryCheckSummary> list(@PathVariable UUID storeId, @RequestParam UUID supplierId) {
        return service.list(storeId, supplierId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse create(@PathVariable UUID storeId, @Valid @RequestBody BakeryCheckRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @GetMapping("/{id}")
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.get(storeId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ACCESS)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }

    @GetMapping("/{id}/comparison")
    @PreAuthorize(ACCESS)
    public BakeryComparison comparison(@PathVariable UUID storeId, @PathVariable UUID id) {
        return comparison.compare(storeId, id);
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse attachDocument(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody BakeryLinkRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.attachDocument(storeId, id, request.id(), principal.getId());
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ACCESS)
    public void detachDocument(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID documentId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.detachDocument(storeId, id, documentId, principal.getId());
    }

    @GetMapping("/{id}/invoice-candidates")
    @PreAuthorize(ACCESS)
    public List<BakeryInvoiceCandidate> invoiceCandidates(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestParam(required = false) String query) {
        return service.invoiceCandidates(storeId, id, query);
    }

    @PostMapping("/{id}/invoices")
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse attachInvoice(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody BakeryLinkRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.attachInvoice(storeId, id, request.id(), principal.getId());
    }

    @DeleteMapping("/{id}/invoices/{invoiceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ACCESS)
    public void detachInvoice(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID invoiceId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.detachInvoice(storeId, id, invoiceId, principal.getId());
    }

    /** Ponowny odczyt numerów WZ z faktur. */
    @PostMapping("/{id}/wz/sync")
    @PreAuthorize(ACCESS)
    public BakeryCheckResponse syncWz(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.syncWz(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/wz")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ACCESS)
    public BakeryWzResponse addWz(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.addWz(storeId, id, principal.getId());
    }

    @PutMapping("/{id}/wz/{wzId}")
    @PreAuthorize(ACCESS)
    public BakeryWzResponse updateWz(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId,
            @Valid @RequestBody BakeryWzRequest request) {
        return service.updateWz(storeId, id, wzId, request);
    }

    @DeleteMapping("/{id}/wz/{wzId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ACCESS)
    public void deleteWz(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.deleteWz(storeId, id, wzId, principal.getId());
    }

    @PostMapping("/{id}/wz/{wzId}/restore")
    @PreAuthorize(ACCESS)
    public BakeryWzResponse restoreWz(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId) {
        return service.restoreWz(storeId, id, wzId);
    }

    @PutMapping("/{id}/wz/{wzId}/scan")
    @PreAuthorize(ACCESS)
    public BakeryWzResponse attachScan(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.attachScan(storeId, id, wzId, file, principal.getId());
    }

    @GetMapping("/{id}/wz/{wzId}/scan")
    @PreAuthorize(ACCESS)
    public ResponseEntity<byte[]> scanFile(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId) {
        DocumentService.ExportFile file = service.scanFile(storeId, id, wzId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @DeleteMapping("/{id}/wz/{wzId}/scan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(ACCESS)
    public void detachScan(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId) {
        service.detachScan(storeId, id, wzId);
    }

    @PostMapping("/{id}/wz/{wzId}/scan/restore")
    @PreAuthorize(ACCESS)
    public BakeryWzResponse restoreScan(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId,
            @Valid @RequestBody BakeryLinkRequest request) {
        return service.restoreScan(storeId, id, wzId, request.id());
    }

    /** Odczyt przypiętego skanu przez AI: propozycja do tabeli, niczego nie zapisuje. */
    @PostMapping("/{id}/wz/{wzId}/scan/read")
    @PreAuthorize(ACCESS)
    public BakeryScanReading readScan(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID wzId) {
        return service.readScan(storeId, id, wzId);
    }
}
