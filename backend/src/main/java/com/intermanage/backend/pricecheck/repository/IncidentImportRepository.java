package com.intermanage.backend.pricecheck.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.pricecheck.entity.IncidentImport;

/** Wgrania incydentów i same incydenty ({@code order_incidents}, bez encji: zapis i odczyt zapytaniami). */
public interface IncidentImportRepository extends JpaRepository<IncidentImport, UUID> {

    @Query("select i from IncidentImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.importedAt desc")
    List<IncidentImport> findLatest(UUID storeId, Limit limit);

    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    /**
     * Zapisuje artykuły z pliku jako incydenty dnia jednym zapytaniem: nowe dopisuje, znane z tego dnia nadpisuje.
     * Zwraca, ile było nowych. Ilości przychodzą tekstem (tablica numeric z JDBC bywa kłopotliwa). ITM8 w tablicy
     * muszą być unikalne - pilnuje tego parser.
     */
    @Query(value = """
            with input as (
                select * from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as varchar[]),
                    cast(:brands as varchar[]), cast(:reasons as text[]), cast(:comments as text[]),
                    cast(:codes as varchar[]), cast(:ordered as varchar[]), cast(:delivered as varchar[]))
                    as r(itm8, ean, name, brand, reason, comment, code, ordered, delivered)
            ), saved as (
                insert into order_incidents (store_id, incident_date, itm8, ean, name, brand, reason, comment,
                    incident_code, ordered_quantity, delivered_quantity, import_id)
                select :storeId, :incidentDate, itm8, ean, name, brand, reason, comment, code,
                    cast(ordered as numeric), cast(delivered as numeric), :importId from input
                on conflict (store_id, incident_date, itm8) do update set ean = excluded.ean, name = excluded.name,
                    brand = excluded.brand, reason = excluded.reason, comment = excluded.comment,
                    incident_code = excluded.incident_code, ordered_quantity = excluded.ordered_quantity,
                    delivered_quantity = excluded.delivered_quantity, import_id = excluded.import_id
                returning (xmax = 0) as created
            )
            select count(*) from saved where created
            """, nativeQuery = true)
    long merge(UUID storeId, UUID importId, LocalDate incidentDate, String[] itm8s, String[] eans, String[] names,
            String[] brands, String[] reasons, String[] comments, String[] codes, String[] ordered,
            String[] delivered);

    interface IncidentItem {
        String getItm8();

        String getEan();

        LocalDate getIncidentDate();

        String getReason();

        String getComment();
    }

    /** Incydenty sklepu z dni od {@code from}, od najstarszego dnia. */
    @Query(value = """
            select o.itm8 as itm8, o.ean as ean, o.incident_date as incident_date, o.reason as reason,
                   o.comment as comment
            from order_incidents o where o.store_id = :storeId and o.incident_date >= :from
            order by o.incident_date""", nativeQuery = true)
    List<IncidentItem> findSince(UUID storeId, LocalDate from);

    @Query(value = "select max(o.incident_date) from order_incidents o where o.store_id = :storeId",
            nativeQuery = true)
    Optional<LocalDate> findLastIncidentDate(UUID storeId);
}
