package com.intermanage.backend.meat.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.meat.entity.MeatDelivery;

public interface MeatDeliveryRepository extends JpaRepository<MeatDelivery, UUID> {

    @Query("select d from MeatDelivery d join fetch d.supplier where d.id = :id and d.storeId = :storeId")
    Optional<MeatDelivery> findInStore(UUID storeId, UUID id);

    /** Aktywne dokumenty sklepu z okresu (data wysyłki), od najnowszego. */
    @Query("""
            select d from MeatDelivery d join fetch d.supplier
            where d.storeId = :storeId and d.deletedAt is null and d.deliveryDate between :from and :to
            order by d.deliveryDate desc, d.createdAt desc""")
    List<MeatDelivery> findInPeriod(UUID storeId, LocalDate from, LocalDate to);

    /** Ten sam dokument dostawcy, już wprowadzony w sklepie (numer bez względu na wielkość liter). */
    @Query("""
            select d from MeatDelivery d where d.storeId = :storeId and d.supplier.id = :supplierId
            and upper(d.documentNumber) = upper(:number) and d.deletedAt is null
            and (:exceptId is null or d.id <> :exceptId)""")
    Optional<MeatDelivery> findActiveDocument(UUID storeId, UUID supplierId, String number, UUID exceptId);

    @Query("select count(l) from MeatDeliveryLine l where l.delivery.id = :deliveryId")
    int countLines(UUID deliveryId);

    interface ReportLine {
        UUID getStoreId();

        LocalDate getDeliveryDate();

        BigDecimal getUnitPrice();

        String getItm8();

        String getName();

        String getBrand();

        Short getVatCode();

        String getDocumentName();
    }

    /** Pozycje aktywnych dokumentów sklepów z okresu, z artykułem z kartoteki sklepu (bez niego puste ITM8). */
    @Query("""
            select d.storeId as storeId, d.deliveryDate as deliveryDate, l.unitPrice as unitPrice, p.itm8 as itm8,
                p.name as name, p.brand as brand, p.vatCode as vatCode, l.documentName as documentName
            from MeatDeliveryLine l join l.delivery d left join l.product p
            where d.storeId in :storeIds and d.deletedAt is null and d.deliveryDate between :from and :to""")
    List<ReportLine> findReportLines(List<UUID> storeIds, LocalDate from, LocalDate to);

    interface SalePrice {
        int getIdx();

        LocalDate getSaleDate();

        BigDecimal getPrice();
    }

    /**
     * Cena sprzedaży brutto za kg każdego artykułu sklepu ({@code stores}, {@code itm8s}, {@code days} to kolejne
     * zapytania): sprzedaż brutto ÷ ilość z ostatniego dnia ze sprzedażą do {@code day} włącznie, ze wszystkich
     * EAN-ów artykułu. {@code saleDate} równy dniu = cena z tego dnia, wcześniejszy = ostatnia znana.
     * {@code idx} od 1; bez sprzedaży do tego dnia wiersza nie ma.
     */
    @Query(value = """
            select k.idx as idx, d.sale_date as saleDate, d.price as price
            from unnest(cast(:stores as uuid[]), cast(:itm8s as varchar[]), cast(:days as date[]))
                with ordinality as k(store_id, itm8, day, idx)
            cross join lateral (
                select s.sale_date, round(sum(s.sale_value_gross) / sum(s.quantity), 2) as price
                from sales s
                where s.store_id = k.store_id and s.itm8 = k.itm8 and s.sale_date <= k.day
                group by s.sale_date
                having sum(s.quantity) > 0 and sum(s.sale_value_gross) > 0
                order by s.sale_date desc
                limit 1
            ) d""", nativeQuery = true)
    List<SalePrice> findSalePrices(UUID[] stores, String[] itm8s, LocalDate[] days);
}
