package com.intermanage.backend.deliorder.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.deliorder.dto.AddDeliOrderItemsRequest;
import com.intermanage.backend.deliorder.dto.ChangeDeliveryDateRequest;
import com.intermanage.backend.deliorder.dto.CorrectDeliOrderRequest;
import com.intermanage.backend.deliorder.dto.DeliDocumentCandidate;
import com.intermanage.backend.deliorder.dto.DeliInvitationResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderSummaryResponse;
import com.intermanage.backend.deliorder.dto.DeliOverviewResponse;
import com.intermanage.backend.deliorder.dto.InviteStoresRequest;
import com.intermanage.backend.deliorder.dto.LinkDeliDocumentRequest;
import com.intermanage.backend.deliorder.dto.MergeDeliOrdersRequest;
import com.intermanage.backend.deliorder.dto.OrderFormResponse;
import com.intermanage.backend.deliorder.dto.SubmitDeliOrderRequest;
import com.intermanage.backend.deliorder.service.DeliInvitationService;
import com.intermanage.backend.deliorder.service.DeliOrderService.ExportFile;
import com.intermanage.backend.deliorder.service.DeliOrderService;
import com.intermanage.backend.product.dto.ProductSearchResponse;
import com.intermanage.backend.store.dto.StoreSummary;

import jakarta.validation.Valid;

/**
 * Zamówienia garmażu i zaproszenia między sklepami. {@code storeId} to sklep, w którym pracuje użytkownik.
 * Podgląd (DELI_ORDERS:VIEW): formularze, wysyłanie zamówień, listy zamówień. Edycja (DELI_ORDERS:EDIT): zaproszenia,
 * korekta i realizacja przychodzących zamówień.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/deli-order")
public class DeliOrderController {

    private static final String VIEW =
            "hasAuthority('DELI_ORDERS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DELI_ORDERS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DeliOrderService orderService;
    private final DeliInvitationService invitationService;

    public DeliOrderController(DeliOrderService orderService, DeliInvitationService invitationService) {
        this.orderService = orderService;
        this.invitationService = invitationService;
    }

    /** Od kogo sklep może zamawiać, ile nowych zamówień przyszło i jakie zaproszenia czekają. */
    @GetMapping("/overview")
    @PreAuthorize(VIEW)
    public DeliOverviewResponse overview(@PathVariable UUID storeId) {
        return orderService.overview(storeId);
    }

    @GetMapping("/suppliers/{formStoreId}/form")
    @PreAuthorize(VIEW)
    public OrderFormResponse supplierForm(@PathVariable UUID storeId, @PathVariable UUID formStoreId) {
        return orderService.supplierForm(storeId, formStoreId);
    }

    @PostMapping("/suppliers/{formStoreId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(VIEW)
    public DeliOrderResponse submit(@PathVariable UUID storeId, @PathVariable UUID formStoreId,
            @Valid @RequestBody SubmitDeliOrderRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.submit(storeId, formStoreId, request, principal.getId());
    }

    /** Kartoteka dostawcy do dopisania pozycji spoza formularza; od 3 znaków, słowa w dowolnej kolejności. */
    @GetMapping("/suppliers/{formStoreId}/products")
    @PreAuthorize(VIEW)
    public List<ProductSearchResponse> supplierProducts(@PathVariable UUID storeId, @PathVariable UUID formStoreId,
            @RequestParam String query) {
        return orderService.searchSupplierProducts(storeId, formStoreId, query);
    }

    /** Plik zamówienia: {@code lst} (EAN-y), {@code xlsx} (EAN, nazwa, ilość) albo {@code pdf} (wydruk). */
    @GetMapping("/orders/{orderId}/export/{format}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @PathVariable String format, @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = orderService.export(storeId, orderId, format, principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @GetMapping("/orders/sent")
    @PreAuthorize(VIEW)
    public List<DeliOrderSummaryResponse> sent(@PathVariable UUID storeId) {
        return orderService.sent(storeId);
    }

    @GetMapping("/orders/incoming")
    @PreAuthorize(VIEW)
    public List<DeliOrderSummaryResponse> incoming(@PathVariable UUID storeId) {
        return orderService.incoming(storeId);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize(VIEW)
    public DeliOrderResponse order(@PathVariable UUID storeId, @PathVariable UUID orderId) {
        return orderService.order(storeId, orderId);
    }

    /** Dostawca otworzył zamówienie: nowe staje się odczytane. */
    @PostMapping("/orders/{orderId}/read")
    @PreAuthorize(VIEW)
    public DeliOrderResponse markRead(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.markRead(storeId, orderId, principal.getId());
    }

    /** Zmiana daty dostawy niezrealizowanego zamówienia: zamawiający z Podglądem, dostawca z Edycją. */
    @PutMapping("/orders/{orderId}/delivery-date")
    @PreAuthorize(VIEW)
    public DeliOrderResponse changeDeliveryDate(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @Valid @RequestBody ChangeDeliveryDateRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.changeDeliveryDate(storeId, orderId, request.deliveryDate(), canEdit(principal),
                principal.getId());
    }

    /** Dostawca łączy zamówienia jednego sklepu na ten sam dzień w jedno. */
    @PostMapping("/orders/merge")
    @PreAuthorize(EDIT)
    public DeliOrderResponse merge(@PathVariable UUID storeId, @Valid @RequestBody MergeDeliOrdersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.merge(storeId, request.orderIds(), principal.getId());
    }

    /** Cofnięcie połączenia: zwraca zamówienia, które wróciły na listy. */
    @PostMapping("/orders/{orderId}/split")
    @PreAuthorize(EDIT)
    public List<DeliOrderSummaryResponse> split(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.split(storeId, orderId, principal.getId());
    }

    /**
     * Usuwa niezrealizowane zamówienie tylko u tego sklepu (dane zostają, druga strona widzi status "usunięte").
     * Zamawiający - z Podglądem, dostawca - z Edycją; zrealizowane - tylko właściciel.
     */
    @DeleteMapping("/orders/{orderId}")
    @PreAuthorize(VIEW)
    public DeliOrderResponse delete(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.delete(storeId, orderId, canEdit(principal), isOwner(principal), principal.getId());
    }

    @PostMapping("/orders/{orderId}/restore")
    @PreAuthorize(VIEW)
    public DeliOrderResponse restore(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.restore(storeId, orderId, canEdit(principal));
    }

    private static boolean isOwner(AppUserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(authority -> AppUserPrincipal.OWNER_AUTHORITY.equals(authority.getAuthority()));
    }

    private static boolean canEdit(AppUserPrincipal principal) {
        return principal.getAuthorities().stream()
                .anyMatch(authority -> "DELI_ORDERS:EDIT".equals(authority.getAuthority()));
    }

    /** Dopisanie produktów, których wcześniej nie zamówiono: zamawiający z Podglądem, dostawca z Edycją. */
    @PostMapping("/orders/{orderId}/items")
    @PreAuthorize(VIEW)
    public DeliOrderResponse addItems(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @Valid @RequestBody AddDeliOrderItemsRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.addItems(storeId, orderId, request, canEdit(principal), principal.getId());
    }

    @PutMapping("/orders/{orderId}/items")
    @PreAuthorize(EDIT)
    public DeliOrderResponse correct(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @Valid @RequestBody CorrectDeliOrderRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.correct(storeId, orderId, request, principal.getId());
    }

    /** Bez treści oznacza same zrealizowanie; z pozycjami najpierw zapisuje zrealizowane ilości. */
    @PostMapping("/orders/{orderId}/fulfill")
    @PreAuthorize(EDIT)
    public DeliOrderResponse fulfill(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CorrectDeliOrderRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.fulfill(storeId, orderId, request, principal.getId());
    }

    /** Ruchy międzymagazynowe dostawcy do przypięcia: z okolic daty dostawy albo pasujące do {@code query}. */
    @GetMapping("/orders/{orderId}/documents/candidates")
    @PreAuthorize(EDIT)
    public List<DeliDocumentCandidate> documentCandidates(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @RequestParam(required = false) String query) {
        return orderService.documentCandidates(storeId, orderId, query);
    }

    /** Dostawca przypina dokument ruchu międzymagazynowego, na którym jedzie zamówienie. */
    @PostMapping("/orders/{orderId}/documents")
    @PreAuthorize(EDIT)
    public DeliOrderResponse linkDocument(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @Valid @RequestBody LinkDeliDocumentRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.linkDocument(storeId, orderId, request.documentId(), principal.getId());
    }

    /** Odpięcie zostawia wpis w historii; przywraca się je ponownym przypięciem. */
    @DeleteMapping("/orders/{orderId}/documents/{documentId}")
    @PreAuthorize(EDIT)
    public DeliOrderResponse unlinkDocument(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @PathVariable UUID documentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return orderService.unlinkDocument(storeId, orderId, documentId, principal.getId());
    }

    @GetMapping("/invitations")
    @PreAuthorize(EDIT)
    public List<DeliInvitationResponse> invitations(@PathVariable UUID storeId) {
        return invitationService.sent(storeId);
    }

    @GetMapping("/invitations/stores")
    @PreAuthorize(EDIT)
    public List<StoreSummary> invitableStores(@PathVariable UUID storeId) {
        return invitationService.invitableStores(storeId);
    }

    @PostMapping("/invitations")
    @PreAuthorize(EDIT)
    public List<DeliInvitationResponse> invite(@PathVariable UUID storeId, @Valid @RequestBody InviteStoresRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return invitationService.invite(storeId, request.storeIds(), principal.getId());
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize(EDIT)
    public List<DeliInvitationResponse> revoke(@PathVariable UUID storeId, @PathVariable UUID invitationId) {
        return invitationService.revoke(storeId, invitationId);
    }

    @PostMapping("/received-invitations/{invitationId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void accept(@PathVariable UUID storeId, @PathVariable UUID invitationId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        invitationService.respond(storeId, invitationId, true, principal.getId());
    }

    @PostMapping("/received-invitations/{invitationId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void decline(@PathVariable UUID storeId, @PathVariable UUID invitationId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        invitationService.respond(storeId, invitationId, false, principal.getId());
    }
}
