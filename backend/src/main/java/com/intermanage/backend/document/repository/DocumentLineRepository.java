package com.intermanage.backend.document.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;

public interface DocumentLineRepository extends JpaRepository<DocumentLine, UUID> {

    /**
     * Linie dokumentów danego typu z ceną zakupu dla podanych ITM8, od najnowszego dokumentu. Pierwsza linia danego
     * ITM8 to jego ostatnia dostawa (kalkulator cen garmażu bierze z niej cenę składnika).
     */
    @Query("""
            select l from DocumentLine l join fetch l.document d
            where d.store.id = :storeId and d.type = :type and l.itm8 in :itm8s
              and l.purchasePrice > 0 and l.quantity > 0
            order by d.documentDate desc, d.number desc, l.lineNumber desc""")
    List<DocumentLine> findPricedLines(UUID storeId, DocumentType type, Collection<String> itm8s);

    /** Cena zakupu artykułu w linii przyjęcia od dostawcy. */
    interface SupplierPrice {
        String getItm8();

        String getEan();

        UUID getDocumentId();

        String getDocumentNumber();

        LocalDateTime getDocumentDate();

        BigDecimal getPurchasePrice();
    }

    /**
     * Ceny zakupu z linii przyjęć PZ od dostawców (kod z linii) z dokumentów z datą w [from, to), od najstarszego
     * (weryfikacja cen: ostatnia cena z bazy przed dostawą od innego dostawcy).
     */
    @Query(value = """
            select l.itm8 as itm8, l.ean as ean, d.id as document_id, d.number as document_number,
                   d.document_date as document_date, l.purchase_price as purchase_price
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.supplier_code in (:supplierCodes)
              and l.purchase_price > 0 and l.quantity > 0
              and d.document_date >= :from and d.document_date < :to
            order by d.document_date, d.number, l.line_number
            """, nativeQuery = true)
    List<SupplierPrice> findSupplierPrices(UUID storeId, Collection<String> supplierCodes, LocalDateTime from,
            LocalDateTime to);

    /** Wartość zakupu linii jednego typu dokumentu w rejonie nomenklatury (pusty kod = linie bez nomenklatury). */
    interface RegionValue {
        String getRegionCode();

        String getType();

        BigDecimal getPurchaseValue();
    }

    /**
     * Wartość zakupu linii dokumentów podanych typów w okresie (wg daty dokumentu, oba końce włącznie), po rejonie
     * nomenklatury linii (pierwsze dwie cyfry kodu). Wydania z magazynu (likwidacje, RW) mają wartość z minusem.
     */
    @Query(value = """
            select coalesce(left(replace(l.nomenclature_code, ' ', ''), 2), '') as region_code, d.type as type,
                   coalesce(sum(l.purchase_value), 0) as purchase_value
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type in (:types)
              and d.document_date >= cast(:from as timestamp)
              and d.document_date < cast(:to as timestamp) + interval '1 day'
            group by 1, 2
            """, nativeQuery = true)
    List<RegionValue> sumByRegion(UUID storeId, Collection<String> types, LocalDate from, LocalDate to);

    /** Dostawca (przy bazie z marką) artykułów z przyjęć PZ: kiedy ostatnio przywiózł i ile z podanych artykułów. */
    interface RecentSupplier {
        String getSupplierCode();

        String getSupplierName();

        String getBrand();

        LocalDateTime getLastDeliveryDate();

        long getProductCount();
    }

    /**
     * Ostatni dostawcy podanych artykułów z przyjęć PZ, od najświeższej dostawy. Dostawcy z {@code brandSuppliers}
     * (baza) są rozbici na marki artykułów, bo gratis daje producent.
     */
    @Query(value = """
            select l.supplier_code as supplier_code, max(l.supplier_name) as supplier_name,
                   case when l.supplier_code in (:brandSuppliers) then nullif(trim(l.brand), '') end as brand,
                   max(d.document_date) as last_delivery_date, count(distinct l.itm8) as product_count
            from document_lines l
            join documents d on d.id = l.document_id
            where d.store_id = :storeId and d.type = 'PZ' and l.itm8 in (:itm8s) and l.quantity > 0
              and l.supplier_code is not null
            group by 1, 3
            order by 4 desc
            limit :limit
            """, nativeQuery = true)
    List<RecentSupplier> findRecentSuppliers(UUID storeId, Collection<String> itm8s,
            Collection<String> brandSuppliers, int limit);
}
