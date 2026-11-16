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
}
