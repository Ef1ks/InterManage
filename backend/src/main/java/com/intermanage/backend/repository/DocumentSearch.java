package com.intermanage.backend.repository;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.entity.Document;
import com.intermanage.backend.entity.DocumentType;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Wyszukiwanie i sortowanie listy dokumentów jednego typu po stronie bazy (dokumentów są tysiące, przeglądarka
 * dostaje je stronami). Zapytanie dzielimy na słowa; każde musi wystąpić jako fragment numeru, dostawcy, jego kodu
 * albo numeru dokumentu dostawcy - bez względu na wielkość liter i polskie znaki ("slezak" znajdzie "ŚLĘZAK").
 */
public final class DocumentSearch {

    /** Kolumny listy, po których można sortować: nazwa z API -> pole encji. */
    private static final Map<String, String> SORTABLE = Map.of(
            "number", "number",
            "date", "documentDate",
            "supplier", "supplierName",
            "supplierDocument", "supplierDocumentNumber",
            "lines", "lineCount",
            "purchase", "purchaseValue",
            "sale", "saleValueGross");

    private static final String POLISH = "ąćęłńóśźż";
    private static final String PLAIN = "acelnoszz";

    private DocumentSearch() {
    }

    /** Słowa zapytania: małe litery bez znaków diakrytycznych i bez znaków specjalnych LIKE. */
    public static List<String> words(String query) {
        if (query == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(query.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(plain.replace("%", "").replace("_", "").trim().split("\\s+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** Sortowanie po kolumnie z listy; nieznana kolumna = po numerze. Puste wartości zawsze na końcu, remis po id. */
    public static Sort sort(String column, String direction) {
        String field = SORTABLE.getOrDefault(column, "number");
        Sort.Order order = "desc".equalsIgnoreCase(direction) ? Sort.Order.desc(field) : Sort.Order.asc(field);
        return Sort.by(order.nullsLast(), Sort.Order.asc("id"));
    }

    public static Specification<Document> inStoreMatching(UUID storeId, DocumentType type, List<String> words) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("store").get("id"), storeId));
            all.add(cb.equal(root.get("type"), type));
            for (String word : words) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(plain(cb, root.get("number")), contains),
                        cb.like(plain(cb, root.get("supplierName")), contains),
                        cb.like(plain(cb, root.get("supplierCode")), contains),
                        cb.like(plain(cb, root.get("supplierDocumentNumber")), contains)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Dokumenty z datą w przedziale [from, to) - podpowiedzi przy przypinaniu do zamówienia garmażu. */
    public static Specification<Document> dated(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("documentDate"), from),
                cb.lessThan(root.get("documentDate"), to));
    }

    /** lower() i polskie litery zamienione na łacińskie (translate w PostgreSQL). */
    private static Expression<String> plain(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("translate", String.class, cb.lower(value), cb.literal(POLISH), cb.literal(PLAIN));
    }
}
