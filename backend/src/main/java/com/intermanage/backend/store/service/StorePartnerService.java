package com.intermanage.backend.store.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.dto.StorePartnersResponse;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Sklepy zaprzyjaźnione: sklep zaprasza inny, zaproszony akceptuje (albo odrzuca). Zaakceptowana para działa w obie
 * strony, np. każdy z nich może wskazać drugi jako sklep docelowy ruchu międzymagazynowego. Odrzucenie, cofnięcie
 * zaproszenia i zakończenie znajomości to znacznik, który da się cofnąć. Każda zmiana zwraca całą listę.
 */
@Service
public class StorePartnerService {

    private final StorePartnerRepository partners;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StoreMapper storeMapper;

    public StorePartnerService(StorePartnerRepository partners, StoreRepository stores, AppUserRepository users,
            StoreMapper storeMapper) {
        this.partners = partners;
        this.stores = stores;
        this.users = users;
        this.storeMapper = storeMapper;
    }

    /** Znajomości i zaproszenia sklepu oraz aktywne sklepy, z którymi nic go jeszcze nie łączy. */
    @Transactional(readOnly = true)
    public StorePartnersResponse partners(UUID storeId) {
        List<StorePartner> found = partners.findActive(storeId);
        Set<UUID> taken = new HashSet<>();
        taken.add(storeId);
        found.forEach(partner -> taken.add(partner.otherThan(storeId).getId()));
        return new StorePartnersResponse(
                found.stream().map(partner -> storeMapper.toPartnerResponse(partner, storeId)).toList(),
                stores.findActiveOrdered().stream()
                        .filter(store -> !taken.contains(store.getId()))
                        .map(storeMapper::toSummary)
                        .toList());
    }

    /** Zaproszony sklep, który wcześniej sam zaprosił ten sklep, nie dostaje drugiego zaproszenia: para już czeka. */
    @Transactional
    public StorePartnersResponse invite(UUID storeId, UUID partnerStoreId, UUID userId) {
        if (storeId.equals(partnerStoreId)) {
            throw ApiException.badRequest("Nie można zaprosić własnego sklepu");
        }
        Store partner = stores.findById(partnerStoreId).filter(Store::isActive)
                .orElseThrow(() -> ApiException.badRequest("Wybrany sklep nie istnieje albo jest wyłączony"));
        if (partners.findActivePair(storeId, partnerStoreId).isPresent()) {
            throw ApiException.conflict("Ten sklep jest już zaprzyjaźniony albo czeka zaproszenie");
        }
        partners.saveAndFlush(new StorePartner(stores.getReferenceById(storeId), partner, user(userId)));
        return partners(storeId);
    }

    /** Akceptuje zaproszenie, które przyszło do sklepu. */
    @Transactional
    public StorePartnersResponse accept(UUID storeId, UUID partnerId, UUID userId) {
        StorePartner partner = active(storeId, partnerId);
        if (!partner.getPartnerStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Zaproszenie akceptuje sklep zaproszony");
        }
        if (partner.isAccepted()) {
            throw ApiException.conflict("Zaproszenie jest już zaakceptowane");
        }
        partner.accept(user(userId));
        return partners(storeId);
    }

    /** Odrzuca albo cofa zaproszenie, albo kończy znajomość (z którejkolwiek strony). */
    @Transactional
    public StorePartnersResponse remove(UUID storeId, UUID partnerId, UUID userId) {
        active(storeId, partnerId).markDeleted(user(userId));
        return partners(storeId);
    }

    /** "Przywróć" po usunięciu: wraca w tym samym stanie (zaakceptowana albo czekająca). */
    @Transactional
    public StorePartnersResponse restore(UUID storeId, UUID partnerId) {
        StorePartner partner = partners.findInStore(storeId, partnerId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zaproszenia"));
        if (partner.getDeletedAt() == null) {
            return partners(storeId);
        }
        if (partners.findActivePair(partner.getStore().getId(), partner.getPartnerStore().getId()).isPresent()) {
            throw ApiException.conflict("Te sklepy mają już nowe zaproszenie");
        }
        partner.restore();
        partners.flush();
        return partners(storeId);
    }

    private StorePartner active(UUID storeId, UUID partnerId) {
        return partners.findInStore(storeId, partnerId)
                .filter(partner -> partner.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zaproszenia"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
