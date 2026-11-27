package com.intermanage.backend.sales.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.Product;

/** Kartoteka sklepu ze wszystkim, co o artykule wiemy, do listy artykułów z wydrukiem. */
public interface ArticleListRepository extends Repository<Product, UUID> {

    interface ArticleRow {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getCatalogSupplier();

        String getRegionCode();

        String getRegionName();

        String getFamilyCode();

        String getFamilyName();

        String getSubfamilyCode();

        String getSubfamilyName();

        BigDecimal getStock();

        BigDecimal getRotation();

        BigDecimal getPurchasePrice();

        BigDecimal getSalePrice();

        Short getVatRate();

        String getUnit();

        BigDecimal getNetContent();

        BigDecimal getCaseSize();

        Boolean getActive();

        Short getDepositCode();

        String getKind();

        Integer getDlv();

        String getCnCode();

        Boolean getCadencier();

        BigDecimal getCadencierPrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();

        Boolean getInLastReport();
    }

    /**
     * Artykuły kartoteki z nazwami nomenklatury, stawką VAT z kodu stawki, cadencier (CENA_CAD i bieżąca lista) i
     * promocją z kasy trwającą {@code today}. {@code inLastReport}: artykuł był w najnowszym raporcie kartoteki.
     */
    @Query(value = """
            with latest as (
                select i.id from product_imports i where i.store_id = :storeId
                order by i.report_generated_at desc limit 1
            )
            select p.itm8 as itm8, p.ean as ean, p.name as name, p.brand as brand, p.supplier as catalogSupplier,
                   p.region_code as regionCode, r.name as regionName, p.family_code as familyCode,
                   f.name as familyName, p.subfamily_code as subfamilyCode, s.name as subfamilyName,
                   p.stock as stock, p.rotation as rotation, p.purchase_price as purchasePrice,
                   p.sale_price as salePrice,
                   cast(case p.vat_code when 1 then 23 when 2 then 8 when 6 then 5 end as smallint) as vatRate,
                   p.unit as unit, p.net_content as netContent, p.case_size as caseSize, p.active as active,
                   p.deposit_code as depositCode, p.kind as kind, p.dlv as dlv, p.cn_code as cnCode,
                   coalesce(p.cadencier_price > 0 and c.itm8 is not null, false) as cadencier,
                   case when p.cadencier_price > 0 then p.cadencier_price end as cadencierPrice,
                   case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                             and (p.promo_from is null or p.promo_from <= cast(:today as date))
                        then p.promo_price end as promoPrice,
                   case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                             and (p.promo_from is null or p.promo_from <= cast(:today as date))
                        then p.promo_from end as promoFrom,
                   case when p.promo_price is not null and p.promo_to >= cast(:today as date)
                             and (p.promo_from is null or p.promo_from <= cast(:today as date))
                        then p.promo_to end as promoTo,
                   (not exists (select 1 from latest) or p.last_seen_import_id = (select id from latest))
                       as inLastReport
            from products p
            left join nomenclatures r on r.store_id = p.store_id and r.code = p.region_code
            left join nomenclatures f on f.store_id = p.store_id and f.code = p.family_code
            left join nomenclatures s on s.store_id = p.store_id and s.code = p.subfamily_code
            left join cadencier_items c on c.store_id = p.store_id and c.itm8 = p.itm8
            where p.store_id = :storeId
            """, nativeQuery = true)
    List<ArticleRow> findArticles(UUID storeId, LocalDate today);
}
