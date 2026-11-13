package com.intermanage.backend.pricecheck.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.pricecheck.entity.CadencierImport;

/** Marże artykułów kartoteki z aktualnych cen: raport artykułów z za niską marżą i aktualnych promocji. */
public interface ArticleMarginRepository extends Repository<CadencierImport, UUID> {

    interface ArticleMargin {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getNomenclatureCode();

        String getRegionCode();

        Short getVatRate();

        BigDecimal getRegularPrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();

        /** {@code LIST} lista promocji, {@code TILL} pliki kasy; pusta bez promocji. */
        String getPromoSource();

        BigDecimal getLastPurchasePrice();

        LocalDateTime getLastPurchaseDate();

        String getSupplierCode();

        String getSupplierName();

        BigDecimal getCatalogPurchasePrice();

        BigDecimal getSoldQuantity();

        boolean getCadencier();

        BigDecimal getCadencierPrice();

        boolean getLeaflet();
    }

    /**
     * Artykuły sklepu z ceną sprzedaży, które żyją: sprzedane od {@code :soldSince} ({@code soldQuantity}, z sum
     * miesięcznych) albo przyjęte na PZ od {@code :deliveredSince} (cena zakupu z przyjęcia od {@code :since}). Flaga aktywności i stan z kartoteki nic tu nie mówią (stany
     * się nie zgadzają), więc się ich nie używa. {@code regularPrice}: aktualna cena zwykła na kasie
     * ({@code products.sale_price}; gdy kartoteka trzyma cenę promocji, która już się skończyła, a kasa nie przysłała
     * jeszcze pliku z powrotem do zwykłej, cena zwykła tej promocji; gdy jest w niej trwająca promocja rozpoczęta przed
     * raportem kartoteki, cena zwykła
     * z listy promocji albo z plików kasy, jak w raporcie marż poza normą); {@code promoPrice} / {@code promoFrom} /
     * {@code promoTo}: promocja trwająca {@code :today}. {@code lastPurchasePrice}: wartość / ilość z ostatniego
     * przyjęcia PZ od {@code :since} (bez gratisów, po korektach przypiętych do przyjęcia jak w raporcie marż poza
     * normą; przyjęcie, w którym po korekcie artykułu nie ma, się nie liczy; linia z lokalnym ITM8 dostawcy należy do
     * artykułu po EAN-ie), z dostawcą linii. VAT z kodu stawki, bez niego z ostatniego przyjęcia. {@code leaflet}:
     * artykuł jest dziś w gazetce (utrzymanie cen z centrali). {@code cadencier}: aktywny w cadencier z CENA_CAD.
     */
    @Query(value = DocumentLineRepository.STORE_ARTICLE_EANS + """
            , lines as (
                select d.id as document_id, d.document_date, d.number as document_number, l.itm8 as line_itm8,
                       coalesce(p.itm8, e.itm8) as itm8,
                       max(l.supplier_code) as supplier_code, max(l.supplier_name) as supplier_name,
                       max(l.vat_rate) as vat_rate, sum(l.quantity) as quantity,
                       sum(l.purchase_value) as purchase_value
                from document_lines l
                join documents d on d.id = l.document_id and d.error_marked_at is null
                left join products p on p.store_id = :storeId and p.itm8 = l.itm8
                left join article_eans e on p.id is null and e.ean = ltrim(btrim(l.ean), '0')
                where d.store_id = :storeId and d.type = 'PZ' and l.quantity > 0 and l.purchase_value > 0
                  and d.document_date >= :since
                group by d.id, d.document_date, d.number, l.itm8, coalesce(p.itm8, e.itm8)
            ), """ + DocumentLineRepository.DELIVERY_FIXES + """
            , delivered as (
                select l.itm8, l.document_date, l.document_number, l.supplier_code, l.supplier_name, l.vat_rate,
                       (l.purchase_value + coalesce(f.purchase_value, 0))
                           / (l.quantity + coalesce(f.quantity, 0)) as price
                from lines l
                left join fixes f on f.delivery_id = l.document_id and f.itm8 = l.line_itm8
                where l.quantity + coalesce(f.quantity, 0) > 0
                  and l.purchase_value + coalesce(f.purchase_value, 0) > 0
            ), last_purchase as (
                select distinct on (itm8) itm8, price, document_date, supplier_code, supplier_name, vat_rate
                from delivered where itm8 is not null
                order by itm8, document_date desc, length(document_number) desc, document_number desc
            ), leaflet as (""" + PromoLeafletRepository.ARTICLE_PERIODS + """
            ), in_leaflet as (
                select distinct itm8 from leaflet
                where date_from <= cast(:today as date) and date_to >= cast(:today as date)
            ), list_regular as (
                select distinct on (pr.itm8) pr.itm8, pr.regular_sale_price as regular, pr.start_date, pr.end_date
                from promotions pr
                join products p on p.store_id = pr.store_id and p.itm8 = pr.itm8 and pr.promo_price = p.sale_price
                where pr.store_id = :storeId
                  and pr.start_date <= cast(:today as date) and pr.end_date >= cast(:today as date)
                order by pr.itm8, pr.end_date desc
            ), till_regular as (
                select distinct on (t.itm8) t.itm8, t.regular_price as regular, t.promo_from, t.promo_to
                from till_promotions t
                join products p on p.store_id = t.store_id and p.itm8 = t.itm8 and t.promo_price = p.sale_price
                where t.store_id = :storeId
                  and t.promo_from <= cast(:today as date) and t.promo_to >= cast(:today as date)
                order by t.itm8, t.file_time desc
            ), list_now as (
                select distinct on (pr.itm8) pr.itm8, pr.promo_price, pr.start_date, pr.end_date
                from promotions pr
                where pr.store_id = :storeId
                  and pr.start_date <= cast(:today as date) and pr.end_date >= cast(:today as date)
                order by pr.itm8, pr.end_date desc
            ), ended_regular as (
                select p.itm8, greatest(coalesce(
                    (select t.regular_price from till_promotions t
                     where t.store_id = p.store_id and t.itm8 = p.itm8 and t.promo_price = p.promo_price
                       and t.promo_to = p.promo_to
                     order by t.file_time desc limit 1),
                    (select pr.regular_sale_price from promotions pr
                     where pr.store_id = p.store_id and pr.itm8 = p.itm8 and pr.promo_price = p.promo_price
                       and pr.end_date = p.promo_to
                     order by pr.start_date desc limit 1)), p.sale_price) as regular
                from products p
                where p.store_id = :storeId and p.promo_price is not null and p.promo_price = p.sale_price
                  and p.promo_to < cast(:today as date)
            ), sold as (
                select m.itm8, sum(m.quantity) as quantity
                from sales_monthly m
                where m.store_id = :storeId and m.month >= cast(:soldSince as date)
                group by m.itm8
            ), articles as (
                select p.*, s.quantity as sold_quantity,
                       p.promo_price is not null and p.promo_to >= cast(:today as date)
                           and (p.promo_from is null or p.promo_from <= cast(:today as date)) as till_promo
                from products p
                left join sold s on s.itm8 = p.itm8
                where p.store_id = :storeId and p.sale_price > 0
                  and (s.itm8 is not null or exists (select 1 from last_purchase u
                                                     where u.itm8 = p.itm8 and u.document_date >= :deliveredSince))
            )
            select p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand,
                   p.subfamily_code as nomenclatureCode, p.region_code as regionCode,
                   cast(coalesce(case p.vat_code when 1 then 23 when 2 then 8 when 6 then 5 end, u.vat_rate)
                        as smallint) as vatRate,
                   coalesce(lr.regular, tr.regular, er.regular, p.sale_price) as regularPrice,
                   case when lr.itm8 is not null or tr.itm8 is not null then p.sale_price
                        when p.till_promo then p.promo_price
                        else ln.promo_price end as promoPrice,
                   case when lr.itm8 is not null then lr.start_date
                        when tr.itm8 is not null then tr.promo_from
                        when p.till_promo then p.promo_from
                        else ln.start_date end as promoFrom,
                   case when lr.itm8 is not null then lr.end_date
                        when tr.itm8 is not null then tr.promo_to
                        when p.till_promo then p.promo_to
                        else ln.end_date end as promoTo,
                   case when lr.itm8 is not null then 'LIST'
                        when tr.itm8 is not null or p.till_promo then 'TILL'
                        when ln.itm8 is not null then 'LIST' end as promoSource,
                   u.price as lastPurchasePrice, u.document_date as lastPurchaseDate,
                   u.supplier_code as supplierCode, u.supplier_name as supplierName,
                   p.purchase_price as catalogPurchasePrice, p.sold_quantity as soldQuantity,
                   coalesce(p.cadencier_price > 0 and c.itm8 is not null, false) as cadencier,
                   case when p.cadencier_price > 0 and c.itm8 is not null then p.cadencier_price end as cadencierPrice,
                   g.itm8 is not null as leaflet
            from articles p
            left join last_purchase u on u.itm8 = p.itm8
            left join cadencier_items c on c.store_id = p.store_id and c.itm8 = p.itm8
            left join in_leaflet g on g.itm8 = p.itm8
            left join list_regular lr on lr.itm8 = p.itm8
            left join till_regular tr on tr.itm8 = p.itm8
            left join list_now ln on ln.itm8 = p.itm8
            left join ended_regular er on er.itm8 = p.itm8
            """, nativeQuery = true)
    List<ArticleMargin> findArticleMargins(UUID storeId, LocalDateTime since, LocalDateTime deliveredSince,
            LocalDate soldSince, LocalDate today,
            LocalDate dataFrom, LocalDate dataTo);
}
