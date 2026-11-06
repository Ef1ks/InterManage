package com.intermanage.backend.deliprice.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.deliprice.dto.AutoLinkResponse;
import com.intermanage.backend.deliprice.dto.CalculationLinkRequest;
import com.intermanage.backend.deliprice.dto.PartnerCalculation;
import com.intermanage.backend.deliprice.dto.PartnerStore;
import com.intermanage.backend.deliprice.entity.PriceCalculation;
import com.intermanage.backend.deliprice.entity.PriceCalculationLink;
import com.intermanage.backend.deliprice.mapper.PriceCalculationMapper;
import com.intermanage.backend.deliprice.repository.PriceCalculationLinkRepository;
import com.intermanage.backend.deliprice.repository.PriceCalculationRepository;
import com.intermanage.backend.deliprice.service.PriceCalculationService.ExportFile;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Kalkulacje sklepów zaprzyjaźnionych: sklep widzi ceny sąsiada, przypisuje jego wyroby do swoich artykułów (kody są
 * lokalne, więc to samo jest pod innym EAN-em i ITM8) i eksportuje te ceny ze swoimi kodami. Automat przypisuje tylko
 * pewne pary ({@link #CERTAIN_SCORE}), resztę przypisuje się ręcznie z propozycji jak we wprowadzaniu danych.
 */
@Service
public class PriceCalculationPartnerService {

    /** Podobieństwo nazw (pg_trgm), od którego para jest pewna; niżej na produkcji zaczynają się inne towary. */
    static final double CERTAIN_SCORE = 0.75;
    /** Ten sam EAN wystarcza przy słabiej podobnej nazwie: nazwy bywają skrócone, ale sam kod nic nie znaczy. */
    static final double SAME_EAN_SCORE = 0.5;
    private static final double SAME_EAN_BONUS = 0.1;

    private final PriceCalculationLinkRepository links;
    private final PriceCalculationRepository calculations;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;
    private final DataEntryLinkService linkSuggestions;
    private final XlsxWriter xlsxWriter;
    private final PriceCalculationMapper mapper;

    public PriceCalculationPartnerService(PriceCalculationLinkRepository links, PriceCalculationRepository calculations,
            ProductRepository products, StoreRepository stores, StorePartnerRepository partners, AppUserRepository users,
            DataEntryLinkService linkSuggestions, XlsxWriter xlsxWriter, PriceCalculationMapper mapper) {
        this.links = links;
        this.calculations = calculations;
        this.products = products;
        this.stores = stores;
        this.partners = partners;
        this.users = users;
        this.linkSuggestions = linkSuggestions;
        this.xlsxWriter = xlsxWriter;
        this.mapper = mapper;
    }

    /** Aktywne sklepy z zaakceptowaną znajomością: każdy to zakładka w kalkulatorze. */
    @Transactional(readOnly = true)
    public List<PartnerStore> stores(UUID storeId) {
        return partners.findActive(storeId).stream()
                .filter(StorePartner::isAccepted)
                .map(partner -> partner.getStore().getId().equals(storeId) ? partner.getPartnerStore() : partner.getStore())
                .filter(Store::isActive)
                .sorted(Comparator.comparing(Store::getNumber))
                .map(mapper::toPartnerStore)
                .toList();
    }

    /** Kalkulacje sąsiada z ich ceną sprzedaży i przypisanym artykułem sklepu. */
    @Transactional(readOnly = true)
    public List<PartnerCalculation> list(UUID storeId, UUID partnerStoreId) {
        requirePartner(storeId, partnerStoreId);
        Map<UUID, PriceCalculationLink> bySource = links.findActive(storeId, partnerStoreId).stream()
                .collect(Collectors.toMap(link -> link.getSource().getId(), Function.identity()));
        return partnerCalculations(partnerStoreId).stream()
                .map(calculation -> mapper.toPartner(calculation, bySource.get(calculation.getProduct().getId())))
                .toList();
    }

    /** Propozycje artykułów sklepu dla nazwy wyrobu sąsiada; ten sam mechanizm co we wprowadzaniu danych. */
    public List<CatalogListItem> suggestions(UUID storeId, String text) {
        return linkSuggestions.suggestions(storeId, text, null);
    }

    /** Przypisuje wyrób sąsiada do artykułu sklepu; poprzednie przypisanie tego wyrobu dostaje znacznik. */
    @Transactional
    public void link(UUID storeId, UUID partnerStoreId, CalculationLinkRequest request, UUID userId) {
        requirePartner(storeId, partnerStoreId);
        Product source = products.findByIdAndStoreId(request.sourceProductId(), partnerStoreId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono wyrobu sklepu zaprzyjaźnionego"));
        Product product = products.findByStoreIdAndItm8(storeId, request.itm8().strip())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece"));
        if (product.getEan() == null || product.getEan().isBlank()) {
            throw ApiException.badRequest("Artykuł nie ma EAN-u, więc nie trafi do eksportu");
        }
        AppUser user = user(userId);
        PriceCalculationLink current = null;
        for (PriceCalculationLink link : links.findActive(storeId, partnerStoreId)) {
            if (link.getSource().getId().equals(source.getId())) {
                current = link;
            } else if (link.getProduct().getId().equals(product.getId())) {
                // Dwa wyroby na jednym artykule dałyby w eksporcie jeden EAN z dwiema cenami.
                throw ApiException.conflict(product.getName() + " jest już przypisany do " + link.getSource().getName());
            }
        }
        if (current != null) {
            if (current.getProduct().getId().equals(product.getId())) {
                return;
            }
            current.delete(user, true);
            links.saveAndFlush(current);
        }
        links.save(new PriceCalculationLink(stores.getReferenceById(storeId), source, product, false, user));
    }

    /** Odpięcie (znacznik); automat nie przypisze tej pary drugi raz. */
    @Transactional
    public void unlink(UUID storeId, UUID id, UUID userId) {
        PriceCalculationLink link = find(storeId, id);
        if (!link.isDeleted()) {
            link.delete(user(userId), true);
        }
    }

    @Transactional
    public void restore(UUID storeId, UUID partnerStoreId, UUID id) {
        requirePartner(storeId, partnerStoreId);
        PriceCalculationLink link = find(storeId, id);
        boolean taken = links.findActive(storeId, partnerStoreId).stream().anyMatch(other ->
                other.getSource().getId().equals(link.getSource().getId())
                        || other.getProduct().getId().equals(link.getProduct().getId()));
        if (taken) {
            throw ApiException.conflict("Ten wyrób albo artykuł ma już inne przypisanie");
        }
        link.restore();
    }

    /**
     * Automatyczne przypisanie pewnych par: najpierw najbardziej podobne, każdy artykuł raz. Wyrób z dwoma równie
     * dobrymi artykułami zostaje do ręcznego wyboru, a pary odpięte przez ludzi się nie powtarzają.
     */
    @Transactional
    public AutoLinkResponse autoLink(UUID storeId, UUID partnerStoreId, UUID userId) {
        requirePartner(storeId, partnerStoreId);
        List<PriceCalculationLink> existing = links.findActive(storeId, partnerStoreId);
        Set<UUID> linkedSources = existing.stream().map(link -> link.getSource().getId()).collect(Collectors.toSet());
        Set<UUID> usedProducts = existing.stream().map(link -> link.getProduct().getId())
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> rejected = links.findRejected(storeId, partnerStoreId).stream()
                .map(link -> pair(link.getSource().getId(), link.getProduct().getId()))
                .collect(Collectors.toSet());

        Map<UUID, List<PriceCalculationLinkRepository.Candidate>> bySource = new HashMap<>();
        for (var candidate : links.findCandidates(storeId, partnerStoreId, SAME_EAN_SCORE)) {
            boolean certain = candidate.getScore() >= CERTAIN_SCORE || candidate.getSameEan();
            if (certain && !linkedSources.contains(candidate.getSourceId())
                    && !rejected.contains(pair(candidate.getSourceId(), candidate.getProductId()))) {
                bySource.computeIfAbsent(candidate.getSourceId(), id -> new ArrayList<>()).add(candidate);
            }
        }
        List<PriceCalculationLinkRepository.Candidate> best = new ArrayList<>();
        for (var candidates : bySource.values()) {
            candidates.sort(Comparator.comparingDouble(PriceCalculationPartnerService::rank).reversed());
            if (candidates.size() == 1 || rank(candidates.get(0)) > rank(candidates.get(1))) {
                best.add(candidates.get(0));
            }
        }
        best.sort(Comparator.comparingDouble(PriceCalculationPartnerService::rank).reversed());

        Store store = stores.getReferenceById(storeId);
        AppUser user = user(userId);
        List<PriceCalculationLink> created = new ArrayList<>();
        for (var candidate : best) {
            if (usedProducts.add(candidate.getProductId())) {
                created.add(new PriceCalculationLink(store, products.getReferenceById(candidate.getSourceId()),
                        products.getReferenceById(candidate.getProductId()), true, user));
            }
        }
        List<UUID> ids = links.saveAll(created).stream().map(PriceCalculationLink::getId).toList();
        return new AutoLinkResponse(ids, partnerCalculations(partnerStoreId).size() - existing.size() - ids.size());
    }

    /** Toast „Cofnij” po automacie: to nie jest odrzucenie pary, więc kolejne uruchomienie przypisze ją znowu. */
    @Transactional
    public void undoAutoLink(UUID storeId, List<UUID> ids, UUID userId) {
        AppUser user = user(userId);
        links.findAllById(ids).stream()
                .filter(link -> link.getId() != null && !link.isDeleted())
                .forEach(link -> find(storeId, link.getId()).delete(user, false));
    }

    /** Ceny sąsiada ze swoimi kodami: EAN i nazwa artykułu sklepu, cena i VAT z kalkulacji sąsiada. */
    @Transactional(readOnly = true)
    public ExportFile export(UUID storeId, UUID partnerStoreId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        requirePartner(storeId, partnerStoreId);
        Store partner = stores.findById(partnerStoreId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Map<UUID, Product> own = links.findActive(storeId, partnerStoreId).stream()
                .collect(Collectors.toMap(link -> link.getSource().getId(), PriceCalculationLink::getProduct));
        List<List<Object>> rows = new ArrayList<>();
        for (PriceCalculation calculation : partnerCalculations(partnerStoreId)) {
            Product product = own.get(calculation.getProduct().getId());
            BigDecimal salePrice = calculation.getSalePrice();
            if (product != null && salePrice != null) {
                rows.add(List.of(product.getEan(), Objects.requireNonNullElse(product.getName(), calculation.getName()),
                        salePrice, BigDecimal.valueOf(calculation.getVatRate())));
            }
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Przypisz najpierw wyroby do swoich artykułów");
        }
        byte[] content = xlsxWriter.write("Ceny garmażu", List.of(
                new XlsxWriter.Column("EAN", 16),
                new XlsxWriter.Column("Nazwa", 42),
                new XlsxWriter.Column("Cena sprzedaży", 14),
                new XlsxWriter.Column("VAT %", 8)), rows);
        return new ExportFile("ceny-garmazu-" + store.getNumber() + "-ze-sklepu-" + partner.getNumber() + "-"
                + LocalDate.now(StoreTime.ZONE) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private static double rank(PriceCalculationLinkRepository.Candidate candidate) {
        return candidate.getScore() + (candidate.getSameEan() ? SAME_EAN_BONUS : 0);
    }

    private static String pair(UUID source, UUID product) {
        return source + ":" + product;
    }

    private List<PriceCalculation> partnerCalculations(UUID partnerStoreId) {
        return PriceCalculationService.sorted(calculations.findActiveWithItems(partnerStoreId)).stream()
                .filter(calculation -> calculation.getProduct() != null)
                .toList();
    }

    private void requirePartner(UUID storeId, UUID partnerStoreId) {
        if (!partners.arePartners(storeId, partnerStoreId)) {
            throw ApiException.forbidden("To nie jest sklep zaprzyjaźniony");
        }
    }

    private PriceCalculationLink find(UUID storeId, UUID id) {
        return links.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypisania"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
