package com.intermanage.backend.sales.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.ArticleListReport;
import com.intermanage.backend.sales.dto.ArticleListRequest;
import com.intermanage.backend.sales.service.ArticleListService;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

/** Lista artykułów kartoteki z filtrami: podgląd, wydruk PDF i Excel. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/article-list")
public class ArticleListController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final ArticleListService service;

    public ArticleListController(ArticleListService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public ArticleListReport report(@PathVariable UUID storeId, @ModelAttribute ArticleListRequest request) {
        return service.report(storeId, request);
    }

    @GetMapping("/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @ModelAttribute ArticleListRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(service.pdf(storeId, request, principal.getId()));
    }

    @GetMapping("/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @ModelAttribute ArticleListRequest request) {
        return file(service.xlsx(storeId, request));
    }

    private static ResponseEntity<byte[]> file(ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
