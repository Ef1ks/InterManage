package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.pricecheck.entity.CadencierItem;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductCode;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Wyszukiwarka kartoteki sklepu. Zapytanie dzielimy na słowa i każde musi pasować do produktu, w dowolnej kolejności
 * i jako fragment: "fil swie" znajdzie "FILET SWIEZY KL A". Słowo pasuje do fragmentu nazwy lub marki, początku EAN
 * (głównego z kartoteki albo każdego obowiązującego z multikodów) albo fragmentu ITM8. {@code *} w słowie to dowolny
 * ciąg znaków, {@code ?} jeden znak; w EAN-ie i ITM8 słowo z nimi jest całym kodem jak w Excelu ({@code 5923532*} =
 * kody od 5923532). Wielkość liter nie ma znaczenia.
 */
public final class ProductSearch {

    /** Najpierw aktywne w systemie sklepu, potem alfabetycznie. */
    public static final Sort ORDER = Sort.by(Sort.Order.desc("active").nullsLast(), Sort.Order.asc("name"));

    /** Słowo z samych cyfr (i gwiazdek) szukamy też wśród wszystkich EAN-ów artykułu z multikodów. */
    private static final Pattern DIGITS = Pattern.compile("[\\d*?]*\\d[\\d*?]*");

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

    /** Produkty sklepu aktywne w kartotece. */
    public static Specification<Product> activeInStore(UUID storeId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("store").get("id"), storeId),
                cb.isTrue(root.get("active")));
    }

    public static Specification<Product> inStoreMatching(UUID storeId, List<String> words) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("store").get("id"), storeId));
            for (String word : words) {
                String pattern = word.replace('*', '%').replace('?', '_');
                boolean wildcard = !pattern.equals(word);
                String contains = "%" + pattern + "%";
                String eanPattern = wildcard ? pattern : pattern + "%";
                List<Predicate> any = new ArrayList<>(List.of(
                        cb.like(cb.lower(root.get("name")), contains),
                        cb.like(cb.lower(root.get("brand")), contains),
                        cb.like(root.get("ean"), eanPattern),
                        cb.like(root.get("itm8"), wildcard ? pattern : contains)));
                if (DIGITS.matcher(word).matches()) {
                    Subquery<Integer> code = query.subquery(Integer.class);
                    Root<ProductCode> c = code.from(ProductCode.class);
                    code.select(cb.literal(1)).where(
                            cb.equal(c.get("storeId"), storeId),
                            cb.isTrue(c.get("current")),
                            cb.equal(c.get("itm8"), root.get("itm8")),
                            cb.like(c.get("ean"), eanPattern));
                    any.add(cb.exists(code));
                }
                all.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Stan w przedziale (granice włącznie, pusta = bez granicy) i znak stanu: POSITIVE, ZERO albo NEGATIVE. */
    public static Specification<Product> withStock(String sign, BigDecimal from, BigDecimal to) {
        return (root, query, cb) -> {
            Path<BigDecimal> stock = root.get("stock");
            List<Predicate> all = new ArrayList<>(between(cb, stock, from, to));
            if ("POSITIVE".equals(sign)) {
                all.add(cb.greaterThan(stock, BigDecimal.ZERO));
            } else if ("ZERO".equals(sign)) {
                all.add(cb.or(cb.isNull(stock), cb.equal(stock, BigDecimal.ZERO)));
            } else if ("NEGATIVE".equals(sign)) {
                all.add(cb.lessThan(stock, BigDecimal.ZERO));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    public static Specification<Product> withRotation(BigDecimal from, BigDecimal to) {
        return (root, query, cb) -> cb.and(between(cb, root.get("rotation"), from, to).toArray(Predicate[]::new));
    }

    /** Podrodzina artykułu zaczyna się od któregoś kodu (rejon "01", rodzina "0101", podrodzina "010101"). */
    public static Specification<Product> inNomenclatures(List<String> codes) {
        return (root, query, cb) -> cb.or(codes.stream()
                .map(code -> cb.like(root.get("subfamilyCode"), code + "%"))
                .toArray(Predicate[]::new));
    }

    /** Artykuł na bieżącej liście cadencier sklepu ({@code present}) albo poza nią. */
    public static Specification<Product> inCadencier(UUID storeId, boolean present) {
        return (root, query, cb) -> {
            Subquery<Integer> item = query.subquery(Integer.class);
            Root<CadencierItem> c = item.from(CadencierItem.class);
            item.select(cb.literal(1)).where(
                    cb.equal(c.get("storeId"), storeId),
                    cb.equal(c.get("itm8"), root.get("itm8")));
            return present ? cb.exists(item) : cb.not(cb.exists(item));
        };
    }

    private static List<Predicate> between(CriteriaBuilder cb, Path<BigDecimal> value, BigDecimal from,
            BigDecimal to) {
        List<Predicate> all = new ArrayList<>();
        if (from != null) {
            all.add(cb.greaterThanOrEqualTo(value, from));
        }
        if (to != null) {
            all.add(cb.lessThanOrEqualTo(value, to));
        }
        return all;
    }
}
