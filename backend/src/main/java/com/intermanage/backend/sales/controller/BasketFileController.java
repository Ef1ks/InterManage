package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
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

import com.intermanage.backend.sales.dto.BasketFileRequest;
import com.intermanage.backend.sales.service.BasketFileService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

import jakarta.validation.Valid;

/**
 * Plik Excel z koszyka zakupowego porównania sprzedaży (dostawcy spoza bazy). Koszyk żyje w przeglądarce, serwer
 * tylko składa z niego arkusz, więc niczego nie zapisuje.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/basket")
public class BasketFileController {

    private final BasketFileService basketFileService;

    public BasketFileController(BasketFileService basketFileService) {
        this.basketFileService = basketFileService;
    }

    @PostMapping("/xlsx")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @Valid @RequestBody BasketFileRequest request) {
        ExportFile file = basketFileService.xlsx(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
