package com.intermanage.backend.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.entity.Product;

import jakarta.persistence.criteria.Predicate;

/**
 * Wyszukiwarka kartoteki sklepu. Zapytanie dzielimy na słowa i każde musi pasować do produktu, w dowolnej kolejności
 * i jako fragment: "fil swie" znajdzie "FILET SWIEZY KL A". Słowo pasuje do fragmentu nazwy lub marki, początku EAN
 * albo fragmentu ITM8. Wielkość liter nie ma znaczenia.
 */
public final class ProductSearch {

    /** Najpierw aktywne w systemie sklepu, potem alfabetycznie. */
    public static final Sort ORDER = Sort.by(Sort.Order.desc("active").nullsLast(), Sort.Order.asc("name"));

    private ProductSearch() {
    }

    /** Słowa zapytania: małe litery, bez znaków specjalnych LIKE (% i _ w nazwach produktów praktycznie nie ma). */
    public static List<String> words(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "").trim().split("\\s+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** Produkty sklepu z działów o podanych początkach NOM ("36" pasuje do "36 60 02"). */
    public static Specification<Product> inStoreWithNomenclature(UUID storeId, List<String> prefixes) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId),
                cb.or(prefixes.stream().map(prefix -> cb.like(root.get("nomenclature"), prefix + "%"))
                        .toArray(Predicate[]::new)));
    }

    public static Specification<Product> inStoreMatching(UUID storeId, List<String> words) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("store").get("id"), storeId));
            for (String word : words) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(cb.lower(root.get("name")), contains),
                        cb.like(cb.lower(root.get("brand")), contains),
                        cb.like(root.get("ean"), word + "%"),
                        cb.like(root.get("itm8"), contains)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }
}
