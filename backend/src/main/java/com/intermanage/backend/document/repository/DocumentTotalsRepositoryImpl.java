package com.intermanage.backend.document.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.document.entity.Document;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/** Implementacja fragmentu {@link DocumentTotalsRepository} (Spring Data łączy ją z {@link DocumentRepository}). */
class DocumentTotalsRepositoryImpl implements DocumentTotalsRepository {

    private final EntityManager entityManager;

    DocumentTotalsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Totals totals(Specification<Document> specification) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Document> root = query.from(Document.class);
        query.multiselect(
                cb.count(root),
                cb.coalesce(cb.sum(root.<BigDecimal>get("purchaseValue")), BigDecimal.ZERO),
                cb.coalesce(cb.sum(root.<BigDecimal>get("saleValueGross")), BigDecimal.ZERO));
        query.where(specification.toPredicate(root, query, cb));
        Tuple row = entityManager.createQuery(query).getSingleResult();
        return new Totals(row.get(0, Long.class), row.get(1, BigDecimal.class), row.get(2, BigDecimal.class));
    }
}
