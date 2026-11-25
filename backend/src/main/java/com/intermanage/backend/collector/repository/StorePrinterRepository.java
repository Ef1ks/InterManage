package com.intermanage.backend.collector.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.StorePrinter;

public interface StorePrinterRepository extends JpaRepository<StorePrinter, UUID> {

    /** Drukarki sklepu: aktywne po nazwie, usunięte na końcu (do przywrócenia). */
    @Query("""
            select p from StorePrinter p left join fetch p.createdBy
            where p.store.id = :storeId
            order by case when p.deletedAt is null then 0 else 1 end, lower(p.name), p.createdAt""")
    List<StorePrinter> findByStore(UUID storeId);

    @Query("""
            select count(p) > 0 from StorePrinter p
            where p.store.id = :storeId and p.mac = :mac and p.deletedAt is null and p.id <> :exceptId""")
    boolean macTaken(UUID storeId, String mac, UUID exceptId);
}
