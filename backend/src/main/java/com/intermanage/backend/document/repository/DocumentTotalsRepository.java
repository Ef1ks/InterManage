package com.intermanage.backend.document.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.document.entity.Document;

/** Sumy do wiersza "Razem" pod listą dokumentów, liczone w bazie dla tego samego filtra co lista. */
public interface DocumentTotalsRepository {

    record Totals(long count, BigDecimal purchaseValue, BigDecimal saleValueGross) {
    }

    Totals totals(Specification<Document> specification);
}
