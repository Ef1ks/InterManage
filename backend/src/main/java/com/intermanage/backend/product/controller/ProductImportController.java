package com.intermanage.backend.product.controller;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.intermanage.backend.common.component.ChunkedUploads;
import com.intermanage.backend.product.dto.EanChangeResponse;
import com.intermanage.backend.product.dto.ImportUploadRequest;
import com.intermanage.backend.product.dto.ImportUploadResponse;
import com.intermanage.backend.product.dto.ProductImportOverview;
import com.intermanage.backend.product.dto.ProductImportJobResponse;
import com.intermanage.backend.product.dto.ProductImportJobResponse.Status;
import com.intermanage.backend.product.service.ProductImportService;

@RestController
@RequestMapping("/api/stores/{storeId}/imports/products")
public class ProductImportController {

    private final ProductImportService importService;
    private final ChunkedUploads uploads;

    public ProductImportController(ProductImportService importService, ChunkedUploads uploads) {
        this.importService = importService;
        this.uploads = uploads;
    }

    /** Liczba produktów sklepu i jego ostatnie importy. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductImportOverview overview(@PathVariable UUID storeId) {
        return importService.overview(storeId);
    }

    @GetMapping("/{id}/ean-changes")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<EanChangeResponse> eanChanges(@PathVariable UUID storeId, @PathVariable UUID id) {
        return importService.eanChanges(storeId, id);
    }

    /** Import idzie w tle (duży raport scala się kilka minut); wynik daje {@link #job}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductImportJobResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return importService.startImport(storeId, file, principal.getId());
    }

    /**
     * Wysyłka pliku w kawałkach (wolne łącze sklepu: jedno żądanie z całym plikiem przekracza limit Cloudflare).
     * Kolejność: {@code POST /uploads}, {@code PUT /uploads/{id}/chunks/{index}} po kolei, {@code POST /uploads/{id}/import}.
     */
    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ImportUploadResponse startUpload(@PathVariable UUID storeId, @Valid @RequestBody ImportUploadRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        String fileName = Path.of(request.fileName()).getFileName().toString();
        importService.checkFile(storeId, fileName);
        UUID id = uploads.start(owner(storeId, principal), fileName, request.size());
        return new ImportUploadResponse(id, ChunkedUploads.CHUNK_SIZE);
    }

    @PutMapping(path = "/uploads/{uploadId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public void uploadChunk(@PathVariable UUID storeId, @PathVariable UUID uploadId, @PathVariable int index,
            @RequestBody byte[] bytes, @AuthenticationPrincipal AppUserPrincipal principal) {
        uploads.append(owner(storeId, principal), uploadId, index, bytes);
    }

    @PostMapping("/uploads/{uploadId}/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductImportJobResponse importUpload(@PathVariable UUID storeId, @PathVariable UUID uploadId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return importService.startImport(storeId, uploads.finish(owner(storeId, principal), uploadId), principal.getId());
    }

    private static String owner(UUID storeId, AppUserPrincipal principal) {
        return storeId + ":" + principal.getId();
    }

    /** Stan importu: 202 w trakcie, 200 z wynikiem, a nieudany import zwraca swój błąd (400, 409…). */
    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ResponseEntity<ProductImportJobResponse> job(@PathVariable UUID storeId, @PathVariable UUID jobId) {
        ProductImportJobResponse job = importService.job(storeId, jobId);
        return ResponseEntity.status(job.status() == Status.RUNNING ? HttpStatus.ACCEPTED : HttpStatus.OK).body(job);
    }
}
