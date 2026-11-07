package com.intermanage.backend.stocktake.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.stocktake.dto.StocktakeImportResponse;
import com.intermanage.backend.stocktake.dto.StocktakeResponse;
import com.intermanage.backend.stocktake.dto.StocktakeSummary;
import com.intermanage.backend.stocktake.service.StocktakeService;

/** Weryfikacja inwentury przed zamknięciem: raporty porównania stanów i lista tego, co trzeba przeliczyć. */
@RestController
@RequestMapping("/api/stores/{storeId}/stocktakes")
public class StocktakeController {

    private final StocktakeService stocktakeService;

    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('INVENTORY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<StocktakeSummary> list(@PathVariable UUID storeId) {
        return stocktakeService.list(storeId);
    }

    /** Raport całego sklepu albo działu; inwenturę rozpoznaje po numerze i dacie z nagłówka. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVENTORY:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public StocktakeImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return stocktakeService.importReport(storeId, file, principal.getId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INVENTORY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public StocktakeResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return stocktakeService.get(storeId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('INVENTORY:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        stocktakeService.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('INVENTORY:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public void restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        stocktakeService.restore(storeId, id);
    }
}
