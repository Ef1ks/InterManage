package com.intermanage.backend.staff.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.dto.TimeRequests;
import com.intermanage.backend.staff.dto.TimeRulesResponse;
import com.intermanage.backend.staff.entity.StaffTimeRuleOffer;
import com.intermanage.backend.staff.entity.StaffTimeStoreRules;
import com.intermanage.backend.staff.repository.StaffTimeRuleOfferRepository;
import com.intermanage.backend.staff.repository.StaffTimeStoreRulesRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zasady odbić sklepu (zaokrąglanie, blokada ponownego odbicia, kto zatwierdza wnioski) i ich propozycje dla sklepów
 * zaprzyjaźnionych: druga strona przyjmuje albo odrzuca, nikt nie zmienia cudzych zasad bez zgody.
 */
@Service
public class StaffTimeRulesService {

    /** Zaokrąglenie musi dzielić godzinę, inaczej granice zmieniałyby się z godziny na godzinę. */
    static final Set<Integer> ROUNDING_MINUTES = Set.of(0, 1, 2, 3, 5, 6, 10, 15, 20, 30, 60);

    private final StaffTimeStoreRulesRepository rules;
    private final StaffTimeRuleOfferRepository offers;
    private final StorePartnerRepository partners;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public StaffTimeRulesService(StaffTimeStoreRulesRepository rules, StaffTimeRuleOfferRepository offers,
            StorePartnerRepository partners, StoreRepository stores, AppUserRepository users) {
        this.rules = rules;
        this.offers = offers;
        this.partners = partners;
        this.stores = stores;
        this.users = users;
    }

    /** Obowiązujące zasady sklepu, bez wpisu domyślne. */
    @Transactional(readOnly = true)
    public StaffTimeStoreRules.Values values(UUID storeId) {
        return rules.findActive(storeId).map(StaffTimeStoreRules::values)
                .orElse(StaffTimeStoreRules.Values.DEFAULT);
    }

    @Transactional(readOnly = true)
    public TimeRulesResponse get(UUID storeId) {
        store(storeId);
        StaffTimeStoreRules active = rules.findActive(storeId).orElse(null);
        StaffTimeStoreRules.Values values = active == null ? StaffTimeStoreRules.Values.DEFAULT : active.values();
        List<TimeRulesResponse.Partner> partnerStores = partners.findActive(storeId).stream()
                .filter(StorePartner::isAccepted)
                .map(partner -> partner.otherThan(storeId))
                .filter(Store::isActive)
                .sorted(Comparator.comparing(Store::getNumber))
                .map(store -> new TimeRulesResponse.Partner(store.getId(), store.getNumber(), store.getName()))
                .toList();
        List<StaffTimeRuleOffer> pending = offers.findPending(storeId);
        return new TimeRulesResponse(values.roundingMinutes(), values.punchCooldownSeconds(), values.finalApprover(),
                active != null, active == null ? null : active.getCreatedAt(),
                active == null || active.getCreatedBy() == null ? null : active.getCreatedBy().getDisplayName(),
                partnerStores,
                pending.stream().filter(offer -> offer.getToStore().getId().equals(storeId))
                        .map(offer -> offer(offer, offer.getFromStore())).toList(),
                pending.stream().filter(offer -> offer.getFromStore().getId().equals(storeId))
                        .map(offer -> offer(offer, offer.getToStore())).toList());
    }

    @Transactional
    public TimeRulesResponse save(UUID storeId, TimeRequests.Rules request, UUID actorId) {
        apply(store(storeId), check(new StaffTimeStoreRules.Values(request.roundingMinutes(),
                request.punchCooldownSeconds(), request.finalApprover())), users.getReferenceById(actorId));
        return get(storeId);
    }

    /** Proponuje bieżące zasady sklepom zaprzyjaźnionym; wcześniejsza oczekująca propozycja do sklepu jest cofana. */
    @Transactional
    public TimeRulesResponse offer(UUID storeId, TimeRequests.RuleOffer request, UUID actorId) {
        Store from = store(storeId);
        AppUser actor = users.getReferenceById(actorId);
        StaffTimeStoreRules.Values values = values(storeId);
        for (UUID targetId : new LinkedHashSet<>(request.storeIds())) {
            if (targetId.equals(storeId) || !partners.arePartners(storeId, targetId)) {
                throw ApiException.badRequest("Zasady można wysłać tylko do sklepu zaprzyjaźnionego");
            }
            Store target = store(targetId);
            offers.findPending(storeId, targetId).ifPresent(previous -> previous.cancel(actor));
            offers.flush();
            offers.save(new StaffTimeRuleOffer(from, target, values, actor));
        }
        return get(storeId);
    }

    /** Sklep, do którego przyszła propozycja, przyjmuje ją: jego zasady stają się takie jak w propozycji. */
    @Transactional
    public TimeRulesResponse accept(UUID storeId, UUID offerId, UUID actorId) {
        StaffTimeRuleOffer offer = pending(offerId);
        if (!offer.getToStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Propozycję przyjmuje sklep, do którego ją wysłano");
        }
        AppUser actor = users.getReferenceById(actorId);
        apply(offer.getToStore(), check(offer.values()), actor);
        offer.accept(actor);
        return get(storeId);
    }

    @Transactional
    public TimeRulesResponse reject(UUID storeId, UUID offerId, UUID actorId) {
        StaffTimeRuleOffer offer = pending(offerId);
        if (!offer.getToStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Propozycję odrzuca sklep, do którego ją wysłano");
        }
        offer.reject(users.getReferenceById(actorId));
        return get(storeId);
    }

    @Transactional
    public TimeRulesResponse cancel(UUID storeId, UUID offerId, UUID actorId) {
        StaffTimeRuleOffer offer = pending(offerId);
        if (!offer.getFromStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Propozycję cofa sklep, który ją wysłał");
        }
        offer.cancel(users.getReferenceById(actorId));
        return get(storeId);
    }

    private void apply(Store store, StaffTimeStoreRules.Values values, AppUser actor) {
        StaffTimeStoreRules current = rules.findActive(store.getId()).orElse(null);
        if (current != null && current.values().equals(values)) {
            return;
        }
        if (current != null) {
            current.markDeleted(actor);
            rules.flush();
        }
        rules.save(new StaffTimeStoreRules(store, values, actor));
    }

    private static StaffTimeStoreRules.Values check(StaffTimeStoreRules.Values values) {
        if (!ROUNDING_MINUTES.contains(values.roundingMinutes())) {
            throw ApiException.badRequest("Zaokrąglenie musi dzielić godzinę: 0, 5, 10, 15, 20, 30 albo 60 min");
        }
        if (values.punchCooldownSeconds() < 10 || values.punchCooldownSeconds() > 600) {
            throw ApiException.badRequest("Blokada ponownego odbicia to od 10 do 600 s");
        }
        return values;
    }

    private StaffTimeRuleOffer pending(UUID offerId) {
        return offers.findById(offerId).filter(StaffTimeRuleOffer::isPending)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiej propozycji"));
    }

    private static TimeRulesResponse.Offer offer(StaffTimeRuleOffer offer, Store other) {
        StaffTimeStoreRules.Values values = offer.values();
        return new TimeRulesResponse.Offer(offer.getId(), other.getId(), other.getNumber(), other.getName(),
                values.roundingMinutes(), values.punchCooldownSeconds(), values.finalApprover(), offer.getCreatedAt(),
                offer.getCreatedBy() == null ? null : offer.getCreatedBy().getDisplayName());
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }
}
