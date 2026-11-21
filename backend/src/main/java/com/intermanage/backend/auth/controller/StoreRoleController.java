package com.intermanage.backend.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.dto.StoreRoleOfferRequest;
import com.intermanage.backend.auth.dto.StoreRoleOrderRequest;
import com.intermanage.backend.auth.dto.StoreRoleRequest;
import com.intermanage.backend.auth.dto.StoreRolesResponse;
import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.service.StoreRoleService;

import jakarta.validation.Valid;

/**
 * Hierarchia sklepu. Odczyt dla każdego z dostępem do sklepu (nazwy rang to słownik), zmiany z {@code STAFF:EDIT}
 * tylko pod własną rangą (sprawdza {@link StoreRoleService}), przesyłanie i przyjmowanie hierarchii Aderon i właściciel.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/roles")
public class StoreRoleController {

    private static final String EDIT = "hasAnyAuthority('STAFF:EDIT', 'STORE:ADMIN', 'ROLE_OWNER') and " + Access.STORE;

    private final StoreRoleService service;

    public StoreRoleController(StoreRoleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Access.STORE)
    public StoreRolesResponse get(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.get(storeId, principal.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public StoreRolesResponse create(@PathVariable UUID storeId, @Valid @RequestBody StoreRoleRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/{roleId}")
    @PreAuthorize(EDIT)
    public StoreRolesResponse update(@PathVariable UUID storeId, @PathVariable UUID roleId,
            @Valid @RequestBody StoreRoleRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.update(storeId, roleId, request, principal.getId());
    }

    @PutMapping("/order")
    @PreAuthorize(EDIT)
    public StoreRolesResponse reorder(@PathVariable UUID storeId, @Valid @RequestBody StoreRoleOrderRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.reorder(storeId, request, principal.getId());
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize(EDIT)
    public StoreRolesResponse delete(@PathVariable UUID storeId, @PathVariable UUID roleId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.delete(storeId, roleId, principal.getId());
    }

    @PostMapping("/{roleId}/restore")
    @PreAuthorize(EDIT)
    public StoreRolesResponse restore(@PathVariable UUID storeId, @PathVariable UUID roleId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restore(storeId, roleId, principal.getId());
    }

    @PostMapping("/offers")
    @PreAuthorize(Access.STORE_ADMIN)
    public StoreRolesResponse offer(@PathVariable UUID storeId, @Valid @RequestBody StoreRoleOfferRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.offer(storeId, request, principal.getId());
    }

    @PostMapping("/offers/{offerId}/accept")
    @PreAuthorize(Access.STORE_ADMIN)
    public StoreRolesResponse accept(@PathVariable UUID storeId, @PathVariable UUID offerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.accept(storeId, offerId, principal.getId());
    }

    @PostMapping("/offers/{offerId}/reject")
    @PreAuthorize(Access.STORE_ADMIN)
    public StoreRolesResponse reject(@PathVariable UUID storeId, @PathVariable UUID offerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.reject(storeId, offerId, principal.getId());
    }

    @DeleteMapping("/offers/{offerId}")
    @PreAuthorize(Access.STORE_ADMIN)
    public StoreRolesResponse cancel(@PathVariable UUID storeId, @PathVariable UUID offerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.cancel(storeId, offerId, principal.getId());
    }

    @PostMapping("/offers/{offerId}/undo")
    @PreAuthorize(Access.STORE_ADMIN)
    public StoreRolesResponse undo(@PathVariable UUID storeId, @PathVariable UUID offerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.undo(storeId, offerId, principal.getId());
    }
}
