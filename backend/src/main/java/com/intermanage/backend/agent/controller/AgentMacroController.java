package com.intermanage.backend.agent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.service.AgentMacroService;
import com.intermanage.backend.agent.service.ProductFileMacroService;
import com.intermanage.backend.auth.security.AppUserPrincipal;

import jakarta.validation.Valid;

/**
 * Makra Mercalys na wolnym komputerze sklepu (agent sklepu), zlecane z przeglądarki, także z innego sklepu.
 * Zlecić może osoba z {@code DATA_ENTRY:EDIT} w tym sklepie (właściciel i Aderon zawsze); stan i komputery widzi
 * {@code DATA_ENTRY:VIEW}.
 *
 * <pre>
 * GET  /api/stores/{storeId}/agent-macros/computers         komputery z agentem: czy mogą wykonać makro
 * POST /api/stores/{storeId}/agent-macros/itm8-collect      {deliveryId, agentId?} zbierz brakujące ITM8 bonu
 * POST /api/stores/{storeId}/agent-macros/itm8-apply        {deliveryId, codes, agentId?} uzupełnij ITM8
 * POST /api/stores/{storeId}/agent-macros/mea-codes         {codes, agentId?} wpisz kody MEA (z listy do wpisania)
 * POST /api/stores/{storeId}/agent-macros/base-receive      {deliveryId, agentId?} wprowadź dostawę z bazy do Mercalys
 * POST /api/stores/{storeId}/agent-macros/base-close        {deliveryId, agentId?} Nr BL i zakończenie dostaw bonu
 * GET  /api/stores/{storeId}/agent-macros/{id}              stan (odliczanie, w toku, wynik, zebrane kody)
 * POST /api/stores/{storeId}/agent-macros/{id}/cancel       anuluje przed startem na komputerze
 * POST /api/stores/{storeId}/agent-macros/{id}/retry        ponownie wczytuje pliki nowych artykułów tego makra
 * </pre>
 */
@RestController
@RequestMapping("/api/stores/{storeId}/agent-macros")
public class AgentMacroController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DATA_ENTRY:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final AgentMacroService macros;

    private final ProductFileMacroService productFiles;

    public AgentMacroController(AgentMacroService macros, ProductFileMacroService productFiles) {
        this.macros = macros;
        this.productFiles = productFiles;
    }

    @GetMapping("/computers")
    @PreAuthorize(VIEW)
    public List<AgentMacroApi.Computer> computers(@PathVariable UUID storeId) {
        return macros.computers(storeId);
    }

    @PostMapping("/itm8-collect")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job itm8Collect(@PathVariable UUID storeId,
            @Valid @RequestBody AgentMacroApi.Itm8CollectRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.itm8Collect(storeId, request, principal.getId());
    }

    @PostMapping("/itm8-apply")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job itm8Apply(@PathVariable UUID storeId,
            @Valid @RequestBody AgentMacroApi.Itm8ApplyRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.itm8Apply(storeId, request, principal.getId());
    }

    @PostMapping("/mea-codes")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job meaCodes(@PathVariable UUID storeId,
            @Valid @RequestBody AgentMacroApi.MeaCodesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.meaCodes(storeId, request, principal.getId());
    }

    @PostMapping("/base-receive")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job baseReceive(@PathVariable UUID storeId,
            @Valid @RequestBody AgentMacroApi.BaseDeliveryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.baseReceive(storeId, request, principal.getId());
    }

    @PostMapping("/base-close")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job baseClose(@PathVariable UUID storeId,
            @Valid @RequestBody AgentMacroApi.BaseDeliveryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.baseClose(storeId, request, principal.getId());
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public AgentMacroApi.Job get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return macros.get(storeId, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job cancel(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return macros.cancel(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize(EDIT)
    public AgentMacroApi.Job retry(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return productFiles.retry(storeId, id, principal.getId());
    }
}
