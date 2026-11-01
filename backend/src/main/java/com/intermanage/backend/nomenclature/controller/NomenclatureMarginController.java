package com.intermanage.backend.nomenclature.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginImportResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginsRequest;
import com.intermanage.backend.nomenclature.service.NomenclatureMarginService;

import jakarta.validation.Valid;

/**
 * Marża wymagana na nomenklaturze (Ustawienia sklepu): odczyt, zapis z ekranu, plik Excel do uzupełnienia i jego
 * wgranie. Cel sprzedaży jak budżet rejonów, więc pod {@code SALES}.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/nomenclature-margins")
public class NomenclatureMarginController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final NomenclatureMarginService service;

    public NomenclatureMarginController(NomenclatureMarginService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<NomenclatureMarginResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PutMapping
    @PreAuthorize(EDIT)
    public List<NomenclatureMarginResponse> save(@PathVariable UUID storeId,
            @Valid @RequestBody NomenclatureMarginsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request, principal.getId());
    }

    /** Plik do uzupełnienia: wszystkie pozycje nomenklatury z kolumną "Marża %". */
    @GetMapping("/template")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> template(@PathVariable UUID storeId) {
        NomenclatureMarginService.ExportFile file = service.template(storeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /** Uzupełniony plik: zastępuje wszystkie progi naraz. */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDIT)
    public NomenclatureMarginImportResponse importFile(@PathVariable UUID storeId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.importFile(storeId, file, principal.getId());
    }
}
