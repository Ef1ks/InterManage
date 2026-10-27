package com.intermanage.backend.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.entity.KsefInvoice;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/** Implementacja fragmentu {@link KsefInvoiceTotalsRepository} (Spring Data łączy ją z {@link KsefInvoiceRepository}). */
class KsefInvoiceTotalsRepositoryImpl implements KsefInvoiceTotalsRepository {

    private final EntityManager entityManager;

    KsefInvoiceTotalsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Totals totals(Specification<KsefInvoice> specification) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<KsefInvoice> root = query.from(KsefInvoice.class);
        query.multiselect(
                cb.count(root),
                cb.coalesce(cb.sum(root.<BigDecimal>get("netAmount")), BigDecimal.ZERO),
                cb.coalesce(cb.sum(root.<BigDecimal>get("grossAmount")), BigDecimal.ZERO));
        query.where(specification.toPredicate(root, query, cb));
        Tuple row = entityManager.createQuery(query).getSingleResult();
        return new Totals(row.get(0, Long.class), row.get(1, BigDecimal.class), row.get(2, BigDecimal.class));
    }
}
