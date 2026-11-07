package com.intermanage.backend.stocktake.component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.intermanage.backend.stocktake.dto.StocktakeBucket;
import com.intermanage.backend.stocktake.dto.StocktakeLineResponse;
import com.intermanage.backend.stocktake.dto.StocktakePairResponse;
import com.intermanage.backend.stocktake.dto.StocktakeReason;

/**
 * Dzieli różnice inwentury na grupy i szuka w nich błędów liczenia. Progi są z testu na inwenturze 10023 z 22.06.2026
 * (19,6 tys. artykułów, kontrola: te same dane z przetasowanymi różnicami):
 * <ul>
 * <li>Para z tą samą ilością i bardzo podobną nazwą to ok. 2/3 trafień ponad przypadek (179 par wobec 67 w kontroli),
 * para spełniająca jedno z tych dwóch ok. 1/3 (261 wobec 170). Reszta (112 wobec 95) to szum, więc nie jest zgłaszana.
 * <li>Grupy wariantów (marka + gramatura z sumą bliską zera) nie dawały nic ponad przypadek (35 wobec ok. 37), dlatego
 * ich tu nie ma. Tak samo sama równa ilość bez podobieństwa artykułów.
 * <li>Rejony 24, 36 i 46 to 62% kwoty, ale to nierozliczona produkcja, a nie liczenie.
 * </ul>
 */
@Component
public class StocktakeAnalyzer {

    /** Artykuł z raportu razem z tym, co wiemy o nim z bazy. */
    public record Article(String itm8, String ean, String name, String brand, String blockType, BigDecimal real,
            BigDecimal theoretical, BigDecimal difference, BigDecimal value, BigDecimal firstValue, String articleKind,
            String regionCode, String regionName, BigDecimal soldAfter, BigDecimal deliveredAfter) {
    }

    public record Result(List<StocktakeLineResponse> lines, List<StocktakePairResponse> pairs,
            Map<StocktakeBucket, Integer> counts, Map<StocktakeBucket, BigDecimal> values) {
    }

    private static final Set<String> PRODUCTION_REGIONS = Set.of("24", "36", "46");
    private static final BigDecimal LARGE_VALUE = BigDecimal.valueOf(100);
    /** Mniejsze różnice to szum: nie szukamy dla nich par. */
    private static final double MIN_PAIR_QUANTITY = 2;
    private static final double MIN_PAIR_VALUE = 5;
    private static final double QUANTITY_TOLERANCE = 0.25;
    private static final double PRICE_RATIO = 1.4;
    private static final double MIN_SIMILARITY = 0.6;
    private static final double STRONG_SIMILARITY = 0.75;
    private static final double SCALE_TOLERANCE = 0.03;
    private static final List<Double> SCALES = List.of(10d, 100d, 1000d);
    private static final EnumSet<StocktakeReason> CONFIRMED = EnumSet.of(StocktakeReason.SOLD_WHEN_ZERO,
            StocktakeReason.SOLD_MORE_THAN_COUNTED);

    public Result analyze(List<Article> articles) {
        Map<String, StocktakeBucket> buckets = new HashMap<>();
        for (Article article : articles) {
            buckets.put(article.itm8(), bucket(article));
        }
        List<StocktakePairResponse> pairs = pairs(articles, buckets);
        Map<String, StocktakeReason> pairReasons = new HashMap<>();
        for (StocktakePairResponse pair : pairs) {
            pairReasons.put(pair.plusItm8(), pair.strength());
            pairReasons.put(pair.minusItm8(), pair.strength());
        }

        Map<StocktakeBucket, Integer> counts = new LinkedHashMap<>();
        Map<StocktakeBucket, BigDecimal> values = new LinkedHashMap<>();
        for (StocktakeBucket bucket : StocktakeBucket.values()) {
            counts.put(bucket, 0);
            values.put(bucket, BigDecimal.ZERO);
        }
        List<StocktakeLineResponse> lines = new ArrayList<>();
        for (Article article : articles) {
            StocktakeBucket bucket = buckets.get(article.itm8());
            List<StocktakeReason> reasons = bucket == StocktakeBucket.RECOUNT
                    ? reasons(article, pairReasons.get(article.itm8())) : List.of();
            if (bucket == StocktakeBucket.RECOUNT && reasons.isEmpty()) {
                bucket = StocktakeBucket.REST;
            }
            counts.merge(bucket, 1, Integer::sum);
            values.merge(bucket, article.value().abs(), BigDecimal::add);
            if (bucket != StocktakeBucket.REST) {
                lines.add(line(article, bucket, reasons));
            }
        }
        // Najpierw twarde dowody, potem według kwoty.
        lines.sort(Comparator.<StocktakeLineResponse, Boolean>comparing(line -> line.reasons().stream()
                .noneMatch(CONFIRMED::contains)).thenComparing(line -> line.value().abs(), Comparator.reverseOrder()));
        return new Result(lines, pairs, counts, values);
    }

    private static StocktakeBucket bucket(Article article) {
        if (article.regionCode() != null && PRODUCTION_REGIONS.contains(article.regionCode())) {
            return StocktakeBucket.PRODUCTION;
        }
        return article.theoretical().signum() < 0 ? StocktakeBucket.SYSTEM_STOCK : StocktakeBucket.RECOUNT;
    }

    private static List<StocktakeReason> reasons(Article article, StocktakeReason pairReason) {
        List<StocktakeReason> reasons = new ArrayList<>();
        boolean sold = article.soldAfter().signum() > 0 && article.deliveredAfter().signum() == 0;
        boolean zero = article.real().signum() == 0;
        if (sold && zero) {
            reasons.add(StocktakeReason.SOLD_WHEN_ZERO);
        } else if (sold && article.soldAfter().compareTo(article.real()) > 0
                && article.difference().compareTo(BigDecimal.ONE.negate()) <= 0) {
            reasons.add(StocktakeReason.SOLD_MORE_THAN_COUNTED);
        }
        if (pairReason != null) {
            reasons.add(pairReason);
        }
        if (digitsSwapped(article.real(), article.theoretical())) {
            reasons.add(StocktakeReason.DIGITS_SWAPPED);
        }
        if (wrongScale(article.real().doubleValue(), article.theoretical().doubleValue())) {
            reasons.add(StocktakeReason.WRONG_SCALE);
        }
        if (zero && !reasons.contains(StocktakeReason.SOLD_WHEN_ZERO)) {
            reasons.add(StocktakeReason.NOT_COUNTED);
        }
        if (reasons.isEmpty() && article.value().abs().compareTo(LARGE_VALUE) >= 0) {
            reasons.add(StocktakeReason.LARGE_DIFFERENCE);
        }
        return reasons;
    }

    private static boolean digitsSwapped(BigDecimal real, BigDecimal theoretical) {
        if (real.compareTo(theoretical) == 0 || real.compareTo(BigDecimal.TEN) < 0
                || theoretical.compareTo(BigDecimal.TEN) < 0 || real.stripTrailingZeros().scale() > 0
                || theoretical.stripTrailingZeros().scale() > 0) {
            return false;
        }
        return Arrays.equals(sortedDigits(real), sortedDigits(theoretical));
    }

    private static char[] sortedDigits(BigDecimal value) {
        char[] digits = value.toBigInteger().toString().toCharArray();
        Arrays.sort(digits);
        return digits;
    }

    private static boolean wrongScale(double real, double theoretical) {
        if (real <= 0 || theoretical <= 0) {
            return false;
        }
        double ratio = real / theoretical;
        for (double scale : SCALES) {
            if (Math.abs(ratio - scale) / scale < SCALE_TOLERANCE || Math.abs(ratio - 1 / scale) * scale < SCALE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** Pary w obrębie marki: najpierw najlepiej pasujące, a każdy artykuł tylko w jednej parze. */
    private static List<StocktakePairResponse> pairs(List<Article> articles, Map<String, StocktakeBucket> buckets) {
        Map<String, List<Candidate>> brands = new HashMap<>();
        for (Article article : articles) {
            String brand = brandKey(article.brand());
            if (brand == null || buckets.get(article.itm8()) != StocktakeBucket.RECOUNT) {
                continue;
            }
            double difference = article.difference().doubleValue();
            double value = Math.abs(article.value().doubleValue());
            if (Math.abs(difference) >= MIN_PAIR_QUANTITY && value >= MIN_PAIR_VALUE) {
                brands.computeIfAbsent(brand, key -> new ArrayList<>())
                        .add(new Candidate(article, difference, value / Math.abs(difference)));
            }
        }
        List<Match> matches = new ArrayList<>();
        for (List<Candidate> brand : brands.values()) {
            for (Candidate plus : brand) {
                if (plus.difference() > 0) {
                    for (Candidate minus : brand) {
                        if (minus.difference() < 0) {
                            match(plus, minus).ifPresent(matches::add);
                        }
                    }
                }
            }
        }
        matches.sort(Comparator.comparingDouble(Match::score).thenComparing(match -> match.plus().article().itm8())
                .thenComparing(match -> match.minus().article().itm8()));

        Set<String> used = new HashSet<>();
        Map<StocktakePairResponse, Double> amounts = new HashMap<>();
        List<StocktakePairResponse> pairs = new ArrayList<>();
        for (Match match : matches) {
            String plus = match.plus().article().itm8();
            String minus = match.minus().article().itm8();
            if (used.contains(plus) || used.contains(minus)) {
                continue;
            }
            used.add(plus);
            used.add(minus);
            boolean sameQuantity = Math.abs(match.plus().difference() + match.minus().difference()) < 1e-6;
            boolean similar = match.similarity() >= STRONG_SIMILARITY;
            if (sameQuantity || similar) {
                StocktakePairResponse pair = new StocktakePairResponse(plus, minus,
                        sameQuantity && similar ? StocktakeReason.SWAP_STRONG : StocktakeReason.SWAP_POSSIBLE);
                pairs.add(pair);
                amounts.put(pair, match.plus().article().value().abs().add(match.minus().article().value().abs())
                        .doubleValue());
            }
        }
        // Na liście najpierw mocne, a w nich największe kwoty. Kolejność z dopasowania mówi tylko, co do czego pasuje.
        pairs.sort(Comparator.comparing(StocktakePairResponse::strength)
                .thenComparing(pair -> amounts.get(pair), Comparator.reverseOrder()));
        return pairs;
    }

    private static java.util.Optional<Match> match(Candidate plus, Candidate minus) {
        double gap = Math.abs(plus.difference() + minus.difference())
                / Math.max(plus.difference(), -minus.difference());
        if (gap > QUANTITY_TOLERANCE || plus.unitCost() == 0 || minus.unitCost() == 0) {
            return java.util.Optional.empty();
        }
        double ratio = plus.unitCost() / minus.unitCost();
        if (ratio > PRICE_RATIO || ratio < 1 / PRICE_RATIO) {
            return java.util.Optional.empty();
        }
        double similarity = similarity(plus.article().name(), minus.article().name());
        return similarity < MIN_SIMILARITY ? java.util.Optional.empty()
                : java.util.Optional.of(new Match(plus, minus, similarity, gap - similarity));
    }

    private record Candidate(Article article, double difference, double unitCost) {
    }

    private record Match(Candidate plus, Candidate minus, double similarity, double score) {
    }

    /** Marka do porównań: bez gwiazdek systemu sklepu; "0" w raporcie oznacza brak marki. */
    private static String brandKey(String brand) {
        if (brand == null) {
            return null;
        }
        String key = brand.replace("*", "").trim().toUpperCase(Locale.ROOT);
        return key.isEmpty() || key.equals("0") ? null : key;
    }

    /** Podobieństwo napisów jak {@code difflib.SequenceMatcher.ratio}: udział wspólnych bloków w obu nazwach. */
    static double similarity(String first, String second) {
        int total = first.length() + second.length();
        return total == 0 ? 1 : 2.0 * common(first, second) / total;
    }

    /** Najdłuższy wspólny fragment, a potem to samo po jego lewej i prawej stronie. */
    private static int common(String first, String second) {
        int best = 0;
        int firstEnd = 0;
        int secondEnd = 0;
        int[] previous = new int[second.length() + 1];
        for (int i = 1; i <= first.length(); i++) {
            int[] current = new int[second.length() + 1];
            for (int j = 1; j <= second.length(); j++) {
                if (first.charAt(i - 1) == second.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > best) {
                        best = current[j];
                        firstEnd = i;
                        secondEnd = j;
                    }
                }
            }
            previous = current;
        }
        if (best == 0) {
            return 0;
        }
        return best + common(first.substring(0, firstEnd - best), second.substring(0, secondEnd - best))
                + common(first.substring(firstEnd), second.substring(secondEnd));
    }

    private static StocktakeLineResponse line(Article article, StocktakeBucket bucket, List<StocktakeReason> reasons) {
        return new StocktakeLineResponse(article.itm8(), article.ean(), article.name(), article.brand(),
                article.regionCode(), article.regionName(), article.real(), article.theoretical(), article.difference(),
                article.value(), article.firstValue(), article.articleKind(), article.blockType(), article.soldAfter(),
                article.deliveredAfter(), bucket, reasons);
    }
}
