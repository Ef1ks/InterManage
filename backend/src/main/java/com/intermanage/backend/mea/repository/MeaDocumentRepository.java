package com.intermanage.backend.mea.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.mea.entity.MeaDocument;

public interface MeaDocumentRepository extends JpaRepository<MeaDocument, UUID> {

    Optional<MeaDocument> findByNumber(String number);

    @EntityGraph(attributePaths = "lines")
    Optional<MeaDocument> findWithLinesByNumber(String number);

    List<MeaDocument> findByNumberIn(Collection<String> numbers);

    /** Pozycja formularza MEA artykułu: numer MEA i ITM8 promocyjny pozycji. */
    interface ArticleLine {
        String getNumber();

        String getMeaItm8();

        Integer getUnitsPerPack();
    }

    /**
     * Pozycje formularzy MEA, których promocja kończy się najwcześniej {@code :today}, należące do artykułu
     * {@code :itm8} sklepu: po ITM8 promocyjnym, ITM8 równoległym (oba bez zer wiodących) albo po EAN-ie artykułu
     * (główny, dawne pary, multikody), od najwcześniejszej promocji.
     */
    @Query(value = DocumentLineRepository.ARTICLE_EANS + """
            select d.number as number, l.mea_itm8 as meaItm8, l.units_per_pack as unitsPerPack
            from mea_documents d
            join mea_document_lines l on l.document_id = d.id
            where d.promo_to >= :today
              and (l.mea_itm8 = ltrim(:itm8, '0')
                   or l.parallel_itm8 = ltrim(:itm8, '0')
                   or ltrim(btrim(l.ean), '0') in (select ean from article_eans where ean <> ''))
            order by d.promo_from nulls last, d.number, l.lp
            """, nativeQuery = true)
    List<ArticleLine> findArticleLines(UUID storeId, String itm8, LocalDate today);

    /**
     * Formularze do pobrania: jeszcze niepobrane, z mniej niż {@code maxAttempts} próbami i bez próby od
     * {@code retryBefore}; najpierw najnowsze promocje (bieżąca praca ważniejsza niż archiwum).
     */
    @Query("""
            select d from MeaDocument d
            where d.fetchedAt is null and d.attempts < :maxAttempts
              and (d.lastAttemptAt is null or d.lastAttemptAt < :retryBefore)
            order by d.promoTo desc nulls last, d.number desc
            """)
    List<MeaDocument> findPending(int maxAttempts, Instant retryBefore, Limit limit);

    /** Gazetka artykułu: MEA z numerem i tytułem albo gazetka sklepu z pliku (numer i tytuł puste). */
    interface ArticleLeaflet {
        String getItm8();

        String getNumber();

        String getTitle();

        LocalDate getPromoFrom();

        LocalDate getPromoTo();
    }

    /**
     * Gazetki artykułów {@code :itm8s} (13 cyfr, jak w kartotece) z promocją nachodzącą na {@code :dataFrom}-
     * {@code :dataTo}: formularze MEA (wspólne dla sieci) po ITM8 promocyjnym, równoległym albo EAN-ie artykułu
     * (główny, dawne pary, multikody), każda gałąź po swoim indeksie (V145, V164), oraz gazetki sklepu z pliku
     * ({@link PromoLeafletRepository#ARTICLE_PERIODS}).
     */
    @Query(value = "with leaflet as (" + PromoLeafletRepository.ARTICLE_PERIODS + """
            ), arts as (
                select distinct unnest(cast(:itm8s as varchar[])) as itm8
            ), codes as (
                select a.itm8, ltrim(btrim(p.ean), '0') as ean
                from arts a join products p on p.store_id = :storeId and p.itm8 = a.itm8
                union
                select a.itm8, ltrim(btrim(pe.ean), '0')
                from arts a join products p on p.store_id = :storeId and p.itm8 = a.itm8
                join product_eans pe on pe.product_id = p.id
                union
                select a.itm8, ltrim(btrim(pc.ean), '0')
                from arts a join product_codes pc on pc.store_id = :storeId and pc.itm8 = a.itm8 and pc.current
            ), mea_lines as (
                select a.itm8, l.document_id from arts a
                join mea_document_lines l on l.mea_itm8 = ltrim(a.itm8, '0')
                union
                select a.itm8, l.document_id from arts a
                join mea_document_lines l on ltrim(l.parallel_itm8, '0') = ltrim(a.itm8, '0')
                union
                select c.itm8, l.document_id from codes c
                join mea_document_lines l on ltrim(btrim(l.ean), '0') = c.ean
                where c.ean <> ''
            )
            select m.itm8 as itm8, d.number as number, d.title as title, d.promo_from as promoFrom,
                   d.promo_to as promoTo
            from mea_lines m join mea_documents d on d.id = m.document_id
            where d.promo_from <= cast(:dataTo as date) and d.promo_to >= cast(:dataFrom as date)
            union
            select x.itm8, null, null, x.date_from, x.date_to
            from leaflet x join arts a on a.itm8 = x.itm8
            order by 1, 4, 2
            """, nativeQuery = true)
    List<ArticleLeaflet> findArticleLeaflets(UUID storeId, String[] itm8s, LocalDate dataFrom, LocalDate dataTo);
}
