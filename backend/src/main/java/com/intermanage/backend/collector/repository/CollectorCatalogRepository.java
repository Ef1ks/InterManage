package com.intermanage.backend.collector.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.Product;

/** Dane kartoteki sklepu do pliku kolektora: artykuły z najnowszego raportu kartoteki, ich EAN-y i promocje. */
public interface CollectorCatalogRepository extends Repository<Product, UUID> {

    /**
     * Ślad danych, z których powstaje plik: ostatnie importy sklepu. Zmienia się tylko po nowym raporcie albo pliku
     * cen, więc kolektor nie pobiera tego samego drugi raz.
     */
    @Query(nativeQuery = true, value = """
            select concat_ws('|',
                (select cast(max(imported_at) as text) from product_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from price_file_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from product_code_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from promotion_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from document_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from cadencier_imports where store_id = :storeId),
                (select cast(max(imported_at) as text) from nomenclature_imports where store_id = :storeId))""")
    String fingerprint(UUID storeId);

    interface Article {
        String getItm8();

        String getEan();

        String getName();

        String getBrand();

        String getSubfamilyCode();

        BigDecimal getSalePrice();

        BigDecimal getPromoPrice();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();

        BigDecimal getStock();

        BigDecimal getRotation();

        BigDecimal getCaseSize();

        String getUnit();

        BigDecimal getNetContent();

        BigDecimal getCadencierPrice();

        Boolean getActive();

        Boolean getCadencier();
    }

    /** Artykuły najnowszego raportu kartoteki, jak na liście Magazynu: najpierw aktywne, alfabetycznie. */
    @Query(nativeQuery = true, value = """
            select p.itm8, p.ean, p.name, p.brand, p.subfamily_code as subfamilyCode, p.sale_price as salePrice,
                   p.promo_price as promoPrice, p.promo_from as promoFrom, p.promo_to as promoTo, p.stock, p.rotation,
                   p.case_size as caseSize, p.unit, p.net_content as netContent,
                   p.cadencier_price as cadencierPrice, p.active, (c.itm8 is not null) as cadencier
            from products p
            left join cadencier_items c on c.store_id = p.store_id and c.itm8 = p.itm8
            where p.store_id = :storeId and p.last_seen_import_id = :importId
            order by coalesce(p.active, true) desc, p.name, p.itm8""")
    List<Article> findArticles(UUID storeId, UUID importId);

    interface Code {
        String getItm8();

        String getEan();

        /** Opakowanie sprzedaży kodu (puszka z czteropaka: 0,25); w dawnych parach nieznane. */
        BigDecimal getPack();

        /** Jednostka sprzedaży kodu, np. Szt albo kg. */
        String getUnit();
    }

    /** Obowiązujące multikody artykułów najnowszego raportu. */
    @Query(nativeQuery = true, value = """
            select c.itm8, c.ean, c.sale_pack as pack, c.sale_unit as unit from product_codes c
            join products p on p.store_id = c.store_id and p.itm8 = c.itm8 and p.last_seen_import_id = :importId
            where c.store_id = :storeId and c.current""")
    List<Code> findCodes(UUID storeId, UUID importId);

    /** Dawne pary ITM8 + EAN artykułów najnowszego raportu (kod działa, gdy nie ma go dziś inny artykuł). */
    @Query(nativeQuery = true, value = """
            select p.itm8, e.ean, cast(null as numeric) as pack,
                   cast(null as varchar) as unit from product_eans e
            join products p on p.id = e.product_id
            where p.store_id = :storeId and p.last_seen_import_id = :importId""")
    List<Code> findFormerCodes(UUID storeId, UUID importId);

    interface Promo {
        String getItm8();

        LocalDate getStartDate();

        LocalDate getEndDate();

        BigDecimal getPromoPrice();

        BigDecimal getRegularPrice();
    }

    /** Trwające i przyszłe promocje z listy promocji, od najwcześniejszej. */
    @Query(nativeQuery = true, value = """
            select itm8, start_date as startDate, effective_end as endDate, promo_price as promoPrice,
                   regular_sale_price as regularPrice
            from promotions
            where store_id = :storeId and effective_end >= :today and effective_end >= start_date
              and promo_price is not null
            order by itm8, start_date, effective_end""")
    List<Promo> findPromotions(UUID storeId, LocalDate today);
}
