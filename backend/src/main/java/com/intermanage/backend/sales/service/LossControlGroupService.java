package com.intermanage.backend.sales.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.sales.dto.LossControlGroupRequest;
import com.intermanage.backend.sales.dto.LossControlGroupResponse;
import com.intermanage.backend.sales.entity.LossControlGroup;
import com.intermanage.backend.sales.entity.LossControlGroupArticle;
import com.intermanage.backend.sales.mapper.LossControlGroupMapper;
import com.intermanage.backend.sales.repository.LossControlGroupArticleRepository;
import com.intermanage.backend.sales.repository.LossControlGroupRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Dostawcy do kontroli strat w raporcie protokołów: grupy artykułów z kolorem, po których raport się filtruje. Każda
 * zmiana zwraca całą listę sklepu.
 */
@Service
public class LossControlGroupService {

    /** Kolory z palety frontu; nazwa koloru trafia do bazy. */
    public static final Set<String> COLORS = Set.of("red", "orange", "amber", "lime", "green", "teal", "sky", "blue",
            "violet", "fuchsia", "pink", "slate");

    private static final UUID NONE = new UUID(0, 0);

    private final LossControlGroupRepository groups;
    private final LossControlGroupArticleRepository articles;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final LossControlGroupMapper mapper;

    public LossControlGroupService(LossControlGroupRepository groups, LossControlGroupArticleRepository articles,
            StoreRepository stores, AppUserRepository users, LossControlGroupMapper mapper) {
        this.groups = groups;
        this.articles = articles;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<LossControlGroupResponse> list(UUID storeId) {
        Map<UUID, List<String>> itm8s = articles.findActiveInStore(storeId).stream()
                .collect(Collectors.groupingBy(article -> article.getGroup().getId(),
                        Collectors.mapping(LossControlGroupArticle::getItm8, Collectors.toList())));
        return groups.findActive(storeId).stream()
                .map(group -> mapper.toResponse(group, itm8s.getOrDefault(group.getId(), List.of())))
                .toList();
    }

    @Transactional
    public List<LossControlGroupResponse> create(UUID storeId, LossControlGroupRequest request, UUID userId) {
        String name = name(storeId, request, NONE);
        groups.save(new LossControlGroup(stores.getReferenceById(storeId), name, color(request), user(userId)));
        return list(storeId);
    }

    @Transactional
    public List<LossControlGroupResponse> update(UUID storeId, UUID id, LossControlGroupRequest request) {
        LossControlGroup group = active(storeId, id);
        group.update(name(storeId, request, id), color(request));
        return list(storeId);
    }

    @Transactional
    public List<LossControlGroupResponse> delete(UUID storeId, UUID id, UUID userId) {
        active(storeId, id).delete(user(userId));
        return list(storeId);
    }

    @Transactional
    public List<LossControlGroupResponse> restore(UUID storeId, UUID id) {
        LossControlGroup group = groups.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy do kontroli"));
        if (group.getDeletedAt() != null) {
            if (groups.existsActiveName(storeId, group.getName(), id)) {
                throw ApiException.conflict("Jest już dostawca o nazwie „" + group.getName() + "”");
            }
            group.restore();
        }
        return list(storeId);
    }

    /** Artykuł już podpięty zostaje z pierwszym wpisem. */
    @Transactional
    public List<LossControlGroupResponse> addArticles(UUID storeId, UUID id, List<String> itm8s, UUID userId) {
        LossControlGroup group = active(storeId, id);
        Set<String> wanted = itm8s(itm8s);
        Set<String> present = articles.findActive(id, wanted).stream().map(LossControlGroupArticle::getItm8)
                .collect(Collectors.toSet());
        AppUser user = user(userId);
        articles.saveAll(wanted.stream().filter(itm8 -> !present.contains(itm8))
                .map(itm8 -> new LossControlGroupArticle(group, itm8, user)).toList());
        return list(storeId);
    }

    @Transactional
    public List<LossControlGroupResponse> removeArticles(UUID storeId, UUID id, List<String> itm8s, UUID userId) {
        active(storeId, id);
        AppUser user = user(userId);
        articles.findActive(id, itm8s(itm8s)).forEach(article -> article.remove(user));
        return list(storeId);
    }

    private LossControlGroup active(UUID storeId, UUID id) {
        return groups.findInStore(storeId, id).filter(group -> group.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy do kontroli"));
    }

    private String name(UUID storeId, LossControlGroupRequest request, UUID exceptId) {
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (groups.existsActiveName(storeId, name, exceptId)) {
            throw ApiException.conflict("Jest już dostawca o nazwie „" + name + "”");
        }
        return name;
    }

    private static String color(LossControlGroupRequest request) {
        if (!COLORS.contains(request.color())) {
            throw ApiException.badRequest("Nieznany kolor");
        }
        return request.color();
    }

    private static Set<String> itm8s(List<String> itm8s) {
        Set<String> result = itm8s.stream().filter(itm8 -> itm8 != null && !itm8.isBlank()).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.isEmpty()) {
            throw ApiException.badRequest("Wybierz artykuły");
        }
        return result;
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
