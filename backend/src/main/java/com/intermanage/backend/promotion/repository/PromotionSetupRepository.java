package com.intermanage.backend.promotion.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.repository.DataEntryCatalogRepository;
import com.intermanage.backend.promotion.entity.PromotionImport;

/** Ceny artykułów do ustawiania promocji. */
public interface PromotionSetupRepository extends JpaRepository<PromotionImport, UUID> {

    interface ArticlePrices {
        String getItm8();

        BigDecimal getRegularPrice();

        BigDecimal getLastPurchasePrice();

        LocalDateTime getLastPurchaseDate();
    }

    /**
     * Cena zwykła i ostatnia cena zakupu artykułów. Trwająca promocja rozpoczęta przed raportem kartoteki zostawia
     * w {@code products.sale_price} cenę promocyjną, więc wtedy cena zwykła idzie z listy promocji albo z plików kasy
     * (promocje założone ręcznie są tylko tam). Ostatnia cena zakupu: wartość / ilość z ostatniego przyjęcia PZ,
     * bez linii gratisowych (wartość 0).
     */
    @Query(value = "with " + DataEntryCatalogRepository.ARTICLE_LINES + """
            , last_purchase as (
                select distinct on (a.article) a.article as itm8, l.purchase_value / l.quantity as price,
                    d.document_date as document_date
                from article_lines a
                join document_lines l on l.id = a.id
                join documents d on d.id = l.document_id and d.error_marked_at is null
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.purchase_value > 0
                order by a.article, d.document_date desc, d.number desc
            )
            select p.itm8 as itm8, coalesce(lp.regular_sale_price, tp.regular_price, p.sale_price) as regularPrice,
                u.price as lastPurchasePrice, u.document_date as lastPurchaseDate
            from products p
            left join last_purchase u on u.itm8 = p.itm8
            left join lateral (
                select pr.regular_sale_price from promotions pr
                where pr.store_id = p.store_id and pr.itm8 = p.itm8 and pr.promo_price = p.sale_price
                    and pr.start_date <= cast(:today as date) and pr.end_date >= cast(:today as date)
                order by pr.end_date desc limit 1) lp on true
            left join lateral (
                select t.regular_price from till_promotions t
                where t.store_id = p.store_id and t.itm8 = p.itm8 and t.promo_price = p.sale_price
                    and t.promo_from <= cast(:today as date) and t.promo_to >= cast(:today as date)
                order by t.file_time desc limit 1) tp on true
            where p.store_id = :storeId and p.itm8 in (:itm8s)""", nativeQuery = true)
    List<ArticlePrices> findPrices(UUID storeId, Collection<String> itm8s, LocalDate today);
}
