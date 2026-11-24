package com.intermanage.backend.cadtop.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.cadtop.dto.CadTopOverview;
import com.intermanage.backend.cadtop.service.CadTopService;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.MurphysoftClient;

/** Plan cadencier i pliki TOP w Imporcie danych. Plan jest wspólny dla sklepów, TOP-y rejonu sklepu. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/cadencier-tops")
public class CadTopController {

    private static final String VIEW = "hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT = "hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final CadTopService service;

    public CadTopController(CadTopService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public CadTopOverview overview(@PathVariable UUID storeId) {
        return service.overview(storeId);
    }

    /** „Sprawdź portal”: nowe pliki TOP sklepu i plany od razu, bez czekania na zadanie w tle. */
    @PostMapping("/sync")
    @PreAuthorize(EDIT)
    public CadTopOverview.Sync sync(@PathVariable UUID storeId) {
        try {
            int tops = service.syncTops(storeId);
            int plans = service.syncPlans(storeId, true);
            return new CadTopOverview.Sync(tops, plans, service.overview(storeId));
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @PostMapping(value = "/plan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDIT)
    public CadTopOverview uploadPlan(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.uploadPlan(storeId, file, principal.getId());
    }
}
