package com.intermanage.backend.cadtop.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.cadtop.entity.CadTopFile;

public interface CadTopFileRepository extends JpaRepository<CadTopFile, UUID> {

    /** Ten plik z portalu (ścieżka i data zmiany) już mamy, także jako zastąpiony nowszym. */
    boolean existsByRegionAndSourcePathAndSourceUpdatedAt(String region, String sourcePath, String sourceUpdatedAt);

    Optional<CadTopFile> findByRegionAndNrCadAndReplacedAtIsNull(String region, String nrCad);

    List<CadTopFile> findByRegionAndReplacedAtIsNull(String region);

    /** Artykuły pliku jednym zapytaniem; ITM8 w tablicy są unikalne (pilnuje tego parser). */
    @Modifying
    @Query(value = """
            insert into cad_top_articles (file_id, itm8, ean, name, brand, supplier_code, product_type,
                purchase_price, sale_price, pack, vat_percent, top_mark, subfamily_code, category, is_new,
                margin_percent, network_rotation)
            select :fileId, r.*
            from unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[]), cast(:names as varchar[]),
                cast(:brands as varchar[]), cast(:suppliers as varchar[]), cast(:types as varchar[]),
                cast(:purchasePrices as numeric[]), cast(:salePrices as numeric[]), cast(:packs as integer[]),
                cast(:vats as numeric[]), cast(:marks as varchar[]), cast(:subfamilies as varchar[]),
                cast(:categories as varchar[]), cast(:news as boolean[]), cast(:margins as numeric[]),
                cast(:rotations as numeric[])) as r
            """, nativeQuery = true)
    int insertArticles(UUID fileId, String[] itm8s, String[] eans, String[] names, String[] brands,
            String[] suppliers, String[] types, BigDecimal[] purchasePrices, BigDecimal[] salePrices,
            Integer[] packs, BigDecimal[] vats, String[] marks, String[] subfamilies, String[] categories,
            Boolean[] news, BigDecimal[] margins, BigDecimal[] rotations);

    interface Counts {
        UUID getFileId();

        int getBtop();

        int getB();

        int getA();

        int getNews();
    }

    /** Ile artykułów pliku ma każde oznaczenie TOP i ile jest nowości. */
    @Query(value = """
            select a.file_id as "fileId", count(*) filter (where a.top_mark = 'BTop') as btop,
                   count(*) filter (where a.top_mark = 'B') as b, count(*) filter (where a.top_mark = 'A') as a,
                   count(*) filter (where a.is_new) as news
            from cad_top_articles a where a.file_id in (:fileIds) group by a.file_id
            """, nativeQuery = true)
    List<Counts> countMarks(Collection<UUID> fileIds);
}
