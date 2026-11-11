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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.supplier.dto.CreateSupplierRequest;
import com.intermanage.backend.supplier.dto.MercalysSupplierCreationRequest;
import com.intermanage.backend.supplier.dto.MercalysSupplierCreationResponse;
import com.intermanage.backend.supplier.dto.MercalysSupplierDraft;
import com.intermanage.backend.supplier.dto.SupplierContactRequest;
import com.intermanage.backend.supplier.dto.SupplierDetails;
import com.intermanage.backend.supplier.dto.SupplierDetailsRequest;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.service.MercalysSupplierService;
import com.intermanage.backend.supplier.service.SupplierDirectoryService;

import jakarta.validation.Valid;

/**
 * Dostawcy sklepu: z listy z systemu sklepu ({@link SupplierImportController}) i dodani ręcznie. Lista służy też do
 * wyboru dostawcy w Dokumentach (gratisy, faktury spoza KSeF) i we wprowadzaniu danych (dostawca dokumentu), a karta dostawcy z kontaktami to moduł Dostawcy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/suppliers")
public class SupplierController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT = "hasAuthority('SUPPLIERS:EDIT')" + STORE;
    /** Zakładanie dostawcy w Mercalys idzie z wprowadzania danych, gdy sprzedawcy z faktury nie ma na liście. */
    private static final String MERCALYS = "hasAnyAuthority('SUPPLIERS:EDIT', 'DATA_ENTRY:VIEW')" + STORE;

    private final SupplierDirectoryService directory;

    private final MercalysSupplierService mercalys;

    public SupplierController(SupplierDirectoryService directory, MercalysSupplierService mercalys) {
        this.directory = directory;
        this.mercalys = mercalys;
    }

    /** Lista dostawców to słownik: wybór dostawcy jest w wielu narzędziach, więc wystarczy dostęp do sklepu. */
    @GetMapping
    @PreAuthorize(Access.STORE)
    public List<SupplierResponse> list(@PathVariable UUID storeId) {
        return directory.list(storeId);
    }

    /** Dane do makra zakładania dostawcy w Mercalys (sprzedawca z faktury spoza listy); niczego nie zapisuje. */
    @GetMapping("/mercalys-draft")
    @PreAuthorize(MERCALYS)
    public MercalysSupplierDraft mercalysDraft(@PathVariable UUID storeId, @RequestParam String nip,
            @RequestParam(required = false) String name) {
        return mercalys.draft(storeId, nip, name);
    }

    /** Makro skopiowane: rezerwuje kod do wgrania nowej listy dostawców. */
    @PostMapping("/mercalys-creations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MERCALYS)
    public MercalysSupplierCreationResponse mercalysCreation(@PathVariable UUID storeId,
            @Valid @RequestBody MercalysSupplierCreationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return mercalys.create(storeId, request, principal.getId());
    }

    /** „Cofnij” po skopiowaniu makra: kod wraca do puli. */
    @DeleteMapping("/mercalys-creations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MERCALYS)
    public void deleteMercalysCreation(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        mercalys.delete(storeId, id, principal.getId());
    }

    /** Dostawca, którego nie ma w systemie sklepu - jest tylko w aplikacji. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('SUPPLIERS:EDIT', 'DOCUMENTS:EDIT')" + STORE)
    public SupplierResponse create(@PathVariable UUID storeId, @Valid @RequestBody CreateSupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return directory.create(storeId, request, principal.getId());
    }

    /** Karta dostawcy z kontaktami: słownik jak lista, zmienia ją tylko {@code SUPPLIERS:EDIT}. */
    @GetMapping("/{supplierId}")
    @PreAuthorize(Access.STORE)
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
