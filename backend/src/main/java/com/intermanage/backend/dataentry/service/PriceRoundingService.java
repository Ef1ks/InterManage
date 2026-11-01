package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.PriceRoundingRequest;
import com.intermanage.backend.dataentry.dto.PriceRoundingRuleResponse;
import com.intermanage.backend.dataentry.entity.PriceRoundingRule;
import com.intermanage.backend.dataentry.mapper.PriceRoundingMapper;
import com.intermanage.backend.dataentry.repository.PriceRoundingRuleRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zaokrąglanie cen sugerowanych według przedziałów cen (Ustawienia sklepu): każdy przedział ma końcówkę, do której
 * cena idzie w górę. Cena spoza przedziałów zostaje bez zmian.
 */
@Service
public class PriceRoundingService {

    private static final Comparator<PriceRoundingRequest.Rule> BY_FROM =
            Comparator.comparing(PriceRoundingRequest.Rule::priceFrom);

    private final PriceRoundingRuleRepository rules;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceRoundingMapper mapper;

    public PriceRoundingService(PriceRoundingRuleRepository rules, StoreRepository stores, AppUserRepository users,
            PriceRoundingMapper mapper) {
        this.rules = rules;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<PriceRoundingRuleResponse> list(UUID storeId) {
        return rules.findByStoreIdAndDeletedAtIsNullOrderByPriceFrom(storeId).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PriceRoundingRule> active(UUID storeId) {
        return rules.findByStoreIdAndDeletedAtIsNullOrderByPriceFrom(storeId);
    }

    /** Cena w górę do końcówki przedziału, w którym jest; poza przedziałami bez zmian. */
    public static BigDecimal round(List<PriceRoundingRule> rules, BigDecimal price) {
        return rules.stream().filter(rule -> rule.covers(price)).findFirst().map(rule -> rule.round(price))
                .orElse(price);
    }

    /** Zastępuje całą listę; poprzednie przedziały dostają znacznik. Bez zmian niczego nie zapisuje. */
    @Transactional
    public List<PriceRoundingRuleResponse> save(UUID storeId, PriceRoundingRequest request, UUID userId) {
        List<PriceRoundingRequest.Rule> sorted = request.rules().stream().sorted(BY_FROM).toList();
        for (int i = 0; i < sorted.size(); i++) {
            PriceRoundingRequest.Rule rule = sorted.get(i);
            if (rule.priceTo() != null && rule.priceTo().compareTo(rule.priceFrom()) <= 0) {
                throw ApiException.badRequest("Przedział od " + rule.priceFrom() + " zł musi kończyć się wyżej, niż "
                        + "się zaczyna");
            }
            if (i > 0) {
                PriceRoundingRequest.Rule previous = sorted.get(i - 1);
                if (previous.priceTo() == null || previous.priceTo().compareTo(rule.priceFrom()) > 0) {
                    throw ApiException.badRequest("Przedziały od " + previous.priceFrom() + " zł i od "
                            + rule.priceFrom() + " zł nachodzą na siebie");
                }
            }
        }

        List<PriceRoundingRule> current = active(storeId);
        if (same(current, sorted)) {
            return list(storeId);
        }
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        current.forEach(rule -> rule.delete(by));
        rules.saveAll(sorted.stream()
                .map(rule -> new PriceRoundingRule(store, rule.priceFrom(), rule.priceTo(), rule.ending(), by))
                .toList());
        rules.flush();
        return list(storeId);
    }

    private static boolean same(List<PriceRoundingRule> current, List<PriceRoundingRequest.Rule> requested) {
        if (current.size() != requested.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            PriceRoundingRule rule = current.get(i);
            PriceRoundingRequest.Rule other = requested.get(i);
            if (rule.getPriceFrom().compareTo(other.priceFrom()) != 0
                    || rule.getEnding().compareTo(other.ending()) != 0
                    || !Objects.equals(rule.getPriceTo() == null, other.priceTo() == null)
                    || rule.getPriceTo() != null && rule.getPriceTo().compareTo(other.priceTo()) != 0) {
                return false;
            }
        }
        return true;
    }
}
