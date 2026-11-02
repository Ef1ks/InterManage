package com.intermanage.backend.product.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductCode;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Wyszukiwarka kartoteki sklepu. Zapytanie dzielimy na słowa i każde musi pasować do produktu, w dowolnej kolejności
 * i jako fragment: "fil swie" znajdzie "FILET SWIEZY KL A". Słowo pasuje do fragmentu nazwy lub marki, początku EAN
 * (głównego z kartoteki albo każdego obowiązującego z multikodów) albo fragmentu ITM8. Wielkość liter nie ma
 * znaczenia.
 */
public final class ProductSearch {

    /** Najpierw aktywne w systemie sklepu, potem alfabetycznie. */
    public static final Sort ORDER = Sort.by(Sort.Order.desc("active").nullsLast(), Sort.Order.asc("name"));

    /** Słowo z samych cyfr szukamy też wśród wszystkich EAN-ów artykułu z multikodów. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

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
                List<Predicate> any = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("name")), contains),
                        cb.like(cb.lower(root.get("brand")), contains),
                        cb.like(root.get("ean"), word + "%"),
                        cb.like(root.get("itm8"), contains)));
                if (DIGITS.matcher(word).matches()) {
                    Subquery<Integer> code = query.subquery(Integer.class);
                    Root<ProductCode> c = code.from(ProductCode.class);
                    code.select(cb.literal(1)).where(
                            cb.equal(c.get("storeId"), storeId),
                            cb.isTrue(c.get("current")),
                            cb.equal(c.get("itm8"), root.get("itm8")),
                            cb.like(c.get("ean"), word + "%"));
                    any.add(cb.exists(code));
                }
                all.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }
}
