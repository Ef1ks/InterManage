package com.intermanage.backend.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.DeliInvitationResponse;
import com.intermanage.backend.dto.StoreSummary;
import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.entity.DeliFormInvitation;
import com.intermanage.backend.entity.InvitationStatus;
import com.intermanage.backend.entity.Store;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.DeliOrderMapper;
import com.intermanage.backend.mapper.StoreMapper;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.repository.DeliFormInvitationRepository;
import com.intermanage.backend.repository.StoreRepository;

/**
 * Zaproszenia do formularza zamówień garmażu. Sklep-dostawca zaprasza inne sklepy, zaproszony sklep akceptuje
 * albo odrzuca. Po akceptacji może zamawiać z formularza dostawcy (patrz {@link DeliOrderService}).
 */
@Service
public class DeliInvitationService {

    private final DeliFormInvitationRepository invitations;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DeliOrderMapper deliOrderMapper;
    private final StoreMapper storeMapper;

    public DeliInvitationService(DeliFormInvitationRepository invitations, StoreRepository stores,
            AppUserRepository users, DeliOrderMapper deliOrderMapper, StoreMapper storeMapper) {
        this.invitations = invitations;
        this.stores = stores;
        this.users = users;
        this.deliOrderMapper = deliOrderMapper;
        this.storeMapper = storeMapper;
    }

    /** Zaproszenia wysłane z formularza sklepu. */
    @Transactional(readOnly = true)
    public List<DeliInvitationResponse> sent(UUID formStoreId) {
        return invitations.findSent(formStoreId).stream().map(deliOrderMapper::toResponse).toList();
    }

    /** Sklepy, które można zaprosić: wszystkie aktywne poza własnym. */
    @Transactional(readOnly = true)
    public List<StoreSummary> invitableStores(UUID formStoreId) {
        return stores.findActiveOrdered().stream()
                .filter(store -> !store.getId().equals(formStoreId))
                .map(storeMapper::toSummary)
                .toList();
    }

    /** Zaprasza sklepy. Odrzucone wcześniej zaproszenie wysyła ponownie, zaakceptowane i czekające zostawia. */
    @Transactional
    public List<DeliInvitationResponse> invite(UUID formStoreId, List<UUID> storeIds, UUID userId) {
        Store formStore = stores.getReferenceById(formStoreId);
        AppUser user = users.getReferenceById(userId);
        for (UUID storeId : storeIds.stream().distinct().toList()) {
            if (storeId.equals(formStoreId)) {
                throw ApiException.badRequest("Nie można zaprosić własnego sklepu - zamawia ze swojego formularza bez zaproszenia");
            }
            Store store = stores.findById(storeId).filter(Store::isActive)
                    .orElseThrow(() -> ApiException.badRequest("Wybrany sklep nie istnieje albo jest wyłączony"));
            invitations.findByFormStoreIdAndStoreId(formStoreId, storeId).ifPresentOrElse(
                    existing -> {
                        if (existing.getStatus() == InvitationStatus.DECLINED) {
                            existing.reinvite(user);
                        }
                    },
                    () -> invitations.save(new DeliFormInvitation(formStore, store, user)));
        }
        invitations.flush();
        return sent(formStoreId);
    }

    /** Cofa zaproszenie albo odbiera dostęp: sklep przestaje widzieć formularz. Złożone zamówienia zostają. */
    @Transactional
    public List<DeliInvitationResponse> revoke(UUID formStoreId, UUID invitationId) {
        invitations.delete(invitations.findByIdAndFormStoreId(invitationId, formStoreId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zaproszenia")));
        invitations.flush();
        return sent(formStoreId);
    }

    @Transactional
    public void respond(UUID storeId, UUID invitationId, boolean accept, UUID userId) {
        DeliFormInvitation invitation = invitations.findByIdAndStoreId(invitationId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zaproszenia"));
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw ApiException.conflict("Na to zaproszenie już odpowiedziano");
        }
        invitation.respond(accept, users.getReferenceById(userId));
    }
}
