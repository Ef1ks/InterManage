package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductPriceChange;

/** Historia cen sprzedaży artykułów (V70). */
public interface ProductPriceChangeRepository extends JpaRepository<ProductPriceChange, UUID> {

    interface Entry {
        LocalDateTime getChangedAt();

        ProductPriceChange.Source getSource();

        BigDecimal getPreviousSalePrice();

        BigDecimal getSalePrice();

        BigDecimal getPreviousPromoPrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();

        String getFileName();
    }

    /** Zmiany ceny artykułu z importów kartoteki od najnowszej, z nazwą raportu; zmiany z kasy są w {@code till_prices}. */
    @Query("""
            select c.changedAt as changedAt, c.source as source, c.previousSalePrice as previousSalePrice,
                   c.salePrice as salePrice, c.previousPromoPrice as previousPromoPrice, c.promoPrice as promoPrice,
                   c.promoFrom as promoFrom, c.promoTo as promoTo,
                   i.fileName as fileName
            from ProductPriceChange c left join c.productImport i
            where c.storeId = :storeId and c.product.id = :productId and c.source = :source
            order by c.changedAt desc nulls last, c.createdAt desc
            """)
    List<Entry> findByProduct(UUID storeId, UUID productId, ProductPriceChange.Source source, Limit limit);
}
