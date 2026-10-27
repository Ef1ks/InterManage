package com.intermanage.backend.ksef.repository;

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.ksef.entity.KsefInvoice;

/** Liczba i sumy faktur pasujących do filtrów listy (wiersz "Razem"), liczone w bazie. */
public interface KsefInvoiceTotalsRepository {

    record Totals(long count, BigDecimal netAmount, BigDecimal grossAmount) {
    }

    Totals totals(Specification<KsefInvoice> specification);
}
