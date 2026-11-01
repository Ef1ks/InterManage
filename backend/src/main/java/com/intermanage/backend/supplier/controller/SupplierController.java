package com.intermanage.backend.supplier.controller;

import java.util.List;
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

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.supplier.dto.CreateSupplierRequest;
import com.intermanage.backend.supplier.dto.SupplierContactRequest;
import com.intermanage.backend.supplier.dto.SupplierDetails;
import com.intermanage.backend.supplier.dto.SupplierDetailsRequest;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.service.SupplierDirectoryService;

import jakarta.validation.Valid;

/**
 * Dostawcy sklepu: z listy z systemu sklepu ({@link SupplierImportController}) i dodani ręcznie. Lista służy też do
 * wyboru dostawcy w Dokumentach (gratisy, faktury spoza KSeF), a karta dostawcy z kontaktami to moduł Dostawcy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/suppliers")
public class SupplierController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAuthority('SUPPLIERS:VIEW')" + STORE;
    private static final String EDIT = "hasAuthority('SUPPLIERS:EDIT')" + STORE;

    private final SupplierDirectoryService directory;

    public SupplierController(SupplierDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SUPPLIERS:VIEW', 'DOCUMENTS:VIEW')" + STORE)
    public List<SupplierResponse> list(@PathVariable UUID storeId) {
        return directory.list(storeId);
    }

    /** Dostawca, którego nie ma w systemie sklepu - jest tylko w aplikacji. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('SUPPLIERS:EDIT', 'DOCUMENTS:EDIT')" + STORE)
    public SupplierResponse create(@PathVariable UUID storeId, @Valid @RequestBody CreateSupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.create(storeId, request, principal.getId());
    }

    @GetMapping("/{supplierId}")
    @PreAuthorize(VIEW)
    public SupplierDetails details(@PathVariable UUID storeId, @PathVariable UUID supplierId) {
        return directory.details(storeId, supplierId);
    }

    @PutMapping("/{supplierId}")
    @PreAuthorize(EDIT)
    public SupplierDetails update(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @Valid @RequestBody SupplierDetailsRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.update(storeId, supplierId, request, principal.getId());
    }

    @PostMapping("/{supplierId}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public SupplierDetails addContact(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @Valid @RequestBody SupplierContactRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.addContact(storeId, supplierId, request, principal.getId());
    }

    @PutMapping("/{supplierId}/contacts/{contactId}")
    @PreAuthorize(EDIT)
    public SupplierDetails updateContact(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @PathVariable UUID contactId, @Valid @RequestBody SupplierContactRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.updateContact(storeId, supplierId, contactId, request, principal.getId());
    }

    /** Usunięcie kontaktu (znacznik). */
    @DeleteMapping("/{supplierId}/contacts/{contactId}")
    @PreAuthorize(EDIT)
    public SupplierDetails deleteContact(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @PathVariable UUID contactId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.deleteContact(storeId, supplierId, contactId, principal.getId());
    }

    @PostMapping("/{supplierId}/contacts/{contactId}/restore")
    @PreAuthorize(EDIT)
    public SupplierDetails restoreContact(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @PathVariable UUID contactId) {
        return directory.restoreContact(storeId, supplierId, contactId);
    }
}
