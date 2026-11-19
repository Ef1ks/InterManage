package com.intermanage.backend.activity.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.intermanage.backend.activity.entity.StoreEvent;
import com.intermanage.backend.activity.entity.StoreEventKind;

public interface StoreEventRepository extends JpaRepository<StoreEvent, UUID>, JpaSpecificationExecutor<StoreEvent> {

    /** Wpisy sklepu, a dla {@code null} wpisy bez sklepu (ustawienia firmy, zadania w tle). */
    static Specification<StoreEvent> inStore(UUID storeId) {
        return (root, query, cb) -> storeId == null ? cb.isNull(root.get("storeId"))
                : cb.equal(root.get("storeId"), storeId);
    }

    /** Następna strona listy od najnowszych: wpisy przed ostatnim pokazanym (czas, potem id). */
    static Specification<StoreEvent> before(Instant at, UUID id) {
        return (root, query, cb) -> at == null ? null : cb.or(
                cb.lessThan(root.get("occurredAt"), at),
                cb.and(cb.equal(root.get("occurredAt"), at), cb.lessThan(root.get("id"), id)));
    }

    static Specification<StoreEvent> ofKind(StoreEventKind kind) {
        return (root, query, cb) -> kind == null ? null : cb.equal(root.get("kind"), kind);
    }

    static Specification<StoreEvent> inModule(String module) {
        return (root, query, cb) -> module == null || module.isBlank() ? null : cb.equal(root.get("module"), module);
    }

    static Specification<StoreEvent> byUser(UUID userId) {
        return (root, query, cb) -> userId == null ? null : cb.equal(root.get("userId"), userId);
    }

    static Specification<StoreEvent> between(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return null;
            }
            if (from == null) {
                return cb.lessThan(root.get("occurredAt"), to);
            }
            return to == null ? cb.greaterThanOrEqualTo(root.get("occurredAt"), from)
                    : cb.and(cb.greaterThanOrEqualTo(root.get("occurredAt"), from),
                            cb.lessThan(root.get("occurredAt"), to));
        };
    }

    /** Każde słowo jako fragment opisu albo ścieżki, bez wielkości liter. */
    static Specification<StoreEvent> matching(String query) {
        return (root, criteria, cb) -> {
            if (query == null || query.isBlank()) {
                return null;
            }
            return cb.and(java.util.Arrays.stream(query.trim().toLowerCase().split("\\s+"))
                    .map(word -> "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                    .map(pattern -> cb.or(cb.like(cb.lower(root.get("summary")), pattern, '\\'),
                            cb.like(cb.lower(root.get("path")), pattern, '\\')))
                    .toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
