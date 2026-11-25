package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.sales.dto.BasketArticleResponse;
import com.intermanage.backend.sales.dto.BasketFileRequest;
import com.intermanage.backend.sales.dto.BasketPreviewRequest;
import com.intermanage.backend.sales.service.BasketFileService;
import com.intermanage.backend.sales.service.BasketPreviewService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

import jakarta.validation.Valid;

/**
 * Koszyk zakupowy z raportów (porównanie sprzedaży, brakujący asortyment, braki towaru): dane wielu artykułów naraz i
 * plik Excel dla dostawców spoza bazy. Koszyk żyje w przeglądarce, więc serwer niczego nie zapisuje.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/basket")
public class BasketFileController {

    private static final String CAN_VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final BasketFileService basketFileService;
    private final BasketPreviewService basketPreviewService;

    public BasketFileController(BasketFileService basketFileService, BasketPreviewService basketPreviewService) {
        this.basketFileService = basketFileService;
        this.basketPreviewService = basketPreviewService;
    }

    /** POST, bo zaznaczonych artykułów bywa kilkaset; niczego nie zmienia. */
    @PostMapping("/preview")
    @PreAuthorize(CAN_VIEW)
    public List<BasketArticleResponse> preview(@PathVariable UUID storeId,
            @Valid @RequestBody BasketPreviewRequest request) {
        return basketPreviewService.preview(storeId, request);
    }

    @PostMapping("/xlsx")
    @PreAuthorize(CAN_VIEW)
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @Valid @RequestBody BasketFileRequest request) {
        ExportFile file = basketFileService.xlsx(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
