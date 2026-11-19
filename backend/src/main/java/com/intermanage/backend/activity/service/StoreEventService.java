package com.intermanage.backend.activity.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.activity.component.StoreEventCatalog;
import com.intermanage.backend.activity.dto.StoreEventFilters;
import com.intermanage.backend.activity.dto.StoreEventPage;
import com.intermanage.backend.activity.dto.StoreEventResponse;
import com.intermanage.backend.activity.entity.StoreEvent;
import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.mapper.StoreEventMapper;
import com.intermanage.backend.activity.repository.StoreEventRepository;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;

@Service
@Transactional(readOnly = true)
public class StoreEventService {

    static final int PAGE_SIZE = 50;
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final StoreEventRepository events;
    private final AppUserRepository users;
    private final StoreEventMapper mapper;

    public StoreEventService(StoreEventRepository events, AppUserRepository users, StoreEventMapper mapper) {
        this.events = events;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * Porcja dziennika sklepu (a dla {@code storeId == null} dziennika firmy) od najnowszych. Daty to dni czasu sklepu,
     * włącznie; {@code beforeAt} i {@code beforeId} to ostatni wpis poprzedniej porcji.
     */
    public StoreEventPage page(UUID storeId, Query query) {
        Instant from = query.from() == null ? null : query.from().atStartOfDay(StoreTime.ZONE).toInstant();
        Instant to = query.to() == null ? null : query.to().plusDays(1).atStartOfDay(StoreTime.ZONE).toInstant();
        Specification<StoreEvent> spec = Specification.allOf(StoreEventRepository.inStore(storeId),
                StoreEventRepository.before(query.beforeAt(), query.beforeId()),
                StoreEventRepository.ofKind(query.kind()), StoreEventRepository.inModule(query.module()),
                StoreEventRepository.byUser(query.userId()), StoreEventRepository.between(from, to),
                StoreEventRepository.matching(query.query()));
        List<StoreEvent> found = events.findAll(spec, PageRequest.of(0, PAGE_SIZE + 1, NEWEST)).getContent();
        boolean hasMore = found.size() > PAGE_SIZE;
        List<StoreEvent> shown = hasMore ? found.subList(0, PAGE_SIZE) : found;
        Map<UUID, String> names = names(shown.stream().map(StoreEvent::getUserId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        List<StoreEventResponse> items = shown.stream()
                .map(event -> mapper.toResponse(event, event.getUserId() == null ? null
                        : names.getOrDefault(event.getUserId(), "Konto usunięte")))
                .toList();
        return new StoreEventPage(items, hasMore);
    }

    /** Moduły i osoby do filtrów: konta z dostępem do sklepu (właściciele zawsze), a dla firmy wszystkie. */
    public StoreEventFilters filters(UUID storeId) {
        List<StoreEventFilters.Option> modules = StoreEventCatalog.modules().entrySet().stream()
                .filter(entry -> storeId == null || !"company".equals(entry.getKey()))
                .map(entry -> new StoreEventFilters.Option(entry.getKey(), entry.getValue()))
                .toList();
        List<StoreEventFilters.Person> people = users.findAllWithRole().stream()
                .filter(user -> storeId == null || user.getRole().isOwner()
                        || user.getStores().stream().anyMatch(store -> store.getId().equals(storeId)))
                .map(user -> new StoreEventFilters.Person(user.getId(), user.getDisplayName()))
                .toList();
        return new StoreEventFilters(modules, people);
    }

    private Map<UUID, String> names(Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AppUser user : users.findAllById(new HashSet<>(ids))) {
                names.put(user.getId(), user.getDisplayName());
            }
        }
        return names;
    }

    public record Query(Instant beforeAt, UUID beforeId, StoreEventKind kind, String module, UUID userId,
            LocalDate from, LocalDate to, String query) {
    }
}
