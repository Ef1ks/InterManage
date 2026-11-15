package com.intermanage.backend.product.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.dto.ArticleLinkRequest;
import com.intermanage.backend.product.dto.ArticleLinkResponse;
import com.intermanage.backend.product.dto.ArticleLinks;
import com.intermanage.backend.product.entity.ArticleStoreLink;
import com.intermanage.backend.product.repository.ArticleStoreLinkRepository;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.service.StoreService;

/**
 * Połączenia tego samego towaru w różnych sklepach (każdy sklep ma własne kody), wspólne dla raportów porównujących
 * sklepy: raportu lady i porównania cen mięsa. Połączyć można artykuły sklepów, do których użytkownik ma dostęp, i
 * sklepów zaprzyjaźnionych z bieżącym; artykuł ma najwyżej jedno połączenie z każdym innym sklepem.
 */
@Service
public class ArticleStoreLinkService {

    private final ArticleStoreLinkRepository links;
    private final StoreService storeService;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;

    public ArticleStoreLinkService(ArticleStoreLinkRepository links, StoreService storeService,
            StorePartnerRepository partners, AppUserRepository users) {
        this.links = links;
        this.storeService = storeService;
        this.partners = partners;
        this.users = users;
    }

    /** Połączenia między sklepami raportu; {@code stores} w kolejności raportu (pierwszy daje klucz wiersza). */
    @Transactional(readOnly = true)
    public ArticleLinks between(List<UUID> stores) {
        if (stores.size() < 2) {
            return ArticleLinks.none();
        }
        return ArticleLinks.of(links.findActiveBetween(stores), stores);
    }

    @Transactional
    public ArticleLinkResponse link(UUID storeId, UUID userId, ArticleLinkRequest request) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.forbidden("Brak dostępu"));
        if (request.storeId().equals(request.linkedStoreId())) {
            throw ApiException.badRequest("Połączyć można tylko artykuły dwóch różnych sklepów");
        }
        checkStores(storeId, user, List.of(request.storeId(), request.linkedStoreId()));
        Side a = new Side(request.storeId(), itm8(request.itm8()));
        Side b = new Side(request.linkedStoreId(), itm8(request.linkedItm8()));
        if (ordered(b, a)) {
            Side swap = a;
            a = b;
            b = swap;
        }
        checkFree(a, b);
        checkFree(b, a);
        return new ArticleLinkResponse(links.save(new ArticleStoreLink(a.storeId, a.itm8, b.storeId, b.itm8, user))
                .getId());
    }

    @Transactional
    public void unlink(UUID storeId, UUID userId, UUID id) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.forbidden("Brak dostępu"));
        ArticleStoreLink link = find(storeId, user, id);
        if (!link.isDeleted()) {
            link.delete(user);
        }
    }

    @Transactional
    public void restore(UUID storeId, UUID userId, UUID id) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.forbidden("Brak dostępu"));
        ArticleStoreLink link = find(storeId, user, id);
        if (!link.isDeleted()) {
            return;
        }
        Side a = new Side(link.getStoreId(), link.getItm8());
        Side b = new Side(link.getLinkedStoreId(), link.getLinkedItm8());
        checkFree(a, b);
        checkFree(b, a);
        link.restore();
    }

    private ArticleStoreLink find(UUID storeId, AppUser user, UUID id) {
        ArticleStoreLink link = links.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono połączenia artykułów"));
        checkStores(storeId, user, List.of(link.getStoreId(), link.getLinkedStoreId()));
        return link;
    }

    /** Artykuł ma najwyżej jedno połączenie z każdym innym sklepem. */
    private void checkFree(Side side, Side other) {
        if (!links.findActiveOf(side.storeId, side.itm8, other.storeId).isEmpty()) {
            throw ApiException.conflict("Artykuł " + side.itm8.replaceFirst("^0+(?=\\d)", "")
                    + " jest już połączony z artykułem drugiego sklepu. Najpierw odłącz poprzednie połączenie.");
        }
    }

    /** Sklepy połączenia: dostępne użytkownikowi albo zaprzyjaźnione z bieżącym. */
    private void checkStores(UUID storeId, AppUser user, Collection<UUID> stores) {
        Set<UUID> allowed = new HashSet<>();
        allowed.add(storeId);
        storeService.accessibleStores(user).stream().map(StoreSummary::id).forEach(allowed::add);
        partners.findActive(storeId).stream()
                .filter(pair -> pair.getAcceptedAt() != null)
                .forEach(pair -> {
                    allowed.add(pair.getStore().getId());
                    allowed.add(pair.getPartnerStore().getId());
                });
        if (!allowed.containsAll(stores)) {
            throw ApiException.forbidden("Połączyć można tylko artykuły swoich sklepów i sklepów zaprzyjaźnionych");
        }
    }

    /** Stała kolejność stron połączenia, żeby indeksy unikalne działały bez względu na to, skąd łączono. */
    private static boolean ordered(Side first, Side second) {
        return first.storeId.toString().compareTo(second.storeId.toString()) < 0;
    }

    private static String itm8(String value) {
        String digits = value.strip();
        return "0".repeat(Math.max(0, 13 - digits.length())) + digits;
    }

    private record Side(UUID storeId, String itm8) {
    }
}
