package com.intermanage.backend.promotion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.promotion.dto.PromotionReport;
import com.intermanage.backend.promotion.dto.PromotionReport.Promotion;
import com.intermanage.backend.promotion.dto.PromotionVerdict;
import com.intermanage.backend.promotion.repository.PromotionReportRepository;
import com.intermanage.backend.promotion.repository.PromotionReportRepository.Row;
import com.intermanage.backend.sales.repository.SaleRepository;

/**
 * Skuteczność promocji: czy promocja dała więcej marży zł, niż artykuł dałby bez niej. Porównuje sprzedaż w promocji
 * (marża FIFO, więc tani zakup pod promocję się liczy) ze sprzedażą dzienną z 28 dni przed nią. Promocje są z listy
 * promocji, a te sprzed jej wgrywania (i spoza niej) rozpoznane ze spadku ceny w sprzedaży.
 * <p>
 * Ocena: promocja jest opłacalna, gdy marża zł w promocji jest co najmniej taka, jak bez promocji. Próg opłacalności
 * to wzrost sprzedaży, przy którym tak się dzieje (marża na sztuce w bazie / w promocji); każda promocja ma swój.
 * Nieopłacalna ze wzrostem poniżej {@link #NO_REACTION_UPLIFT} to brak reakcji klientów, powyżej - za głęboka obniżka.
 */
@Service
public class PromotionReportService {

    /** Rozpoznawanie ze sprzedaży czyta ok. 2 s na miesiąc okresu, więc najwyżej kwartał. */
    public static final int MAX_PERIOD_DAYS = 92;
    static final int BASE_DAYS = 28;
    static final int AFTER_DAYS = 14;
    /** Baza krótsza niż tyle dni (reszta to inne promocje) nie wystarcza do oceny. */
    static final int MIN_BASE_DAYS = 14;
    static final BigDecimal NO_REACTION_UPLIFT = new BigDecimal("1.2");
    /** Dzień jest promocyjny, gdy cena dnia jest niższa niż 85% ceny zwykłej (obniżka ponad 15%). */
    static final BigDecimal PRICE_FACTOR = new BigDecimal("0.85");
    static final int MIN_REFERENCE_DAYS = 10;
    static final int GAP_DAYS = 3;
    static final int MIN_DAYS = 3;
    static final int MAX_ESTIMATED_DAYS = 31;
    /** Cena zwykła patrzy na dwa miesiące wstecz. */
    static final int REFERENCE_MONTHS = 2;
    /** Sprzedaż po promocji oceniamy dopiero z tylu dni. */
    static final int MIN_AFTER_DAYS = 7;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PromotionReportRepository repository;
    private final SaleRepository sales;

    public PromotionReportService(PromotionReportRepository repository, SaleRepository sales) {
        this.repository = repository;
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public PromotionReport report(UUID storeId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("Data „od” jest późniejsza niż „do”");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw ApiException.badRequest("Okres może mieć najwyżej " + MAX_PERIOD_DAYS + " dni");
        }
        LocalDate lastDay = sales.findCoverage(storeId).getLastDate();
        LocalDate listSince = repository.findFirstImportDate(storeId);
        LocalDate end = lastDay == null || to.isBefore(lastDay) ? to : lastDay;
        if (lastDay == null || end.isBefore(from)) {
            return report(from, to, lastDay, listSince, List.of());
        }

        repository.tuneForReport();
        LocalDate start = from.minusDays(MAX_ESTIMATED_DAYS);
        LocalDate earliest = repository.findEarliestStart(storeId, from, end);
        if (earliest != null && earliest.isBefore(start)) {
            start = earliest;
        }
        LocalDate dataFrom = start.minusDays(BASE_DAYS);
        LocalDate dataTo = end.plusDays(AFTER_DAYS).isAfter(lastDay) ? lastDay : end.plusDays(AFTER_DAYS);
        LocalDate referenceFrom = dataFrom.withDayOfMonth(1).minusMonths(REFERENCE_MONTHS);

        List<Promotion> promotions = repository
                .findPromotions(storeId, from, end, lastDay, dataFrom, dataTo, referenceFrom, PRICE_FACTOR,
                        MIN_REFERENCE_DAYS, GAP_DAYS, MIN_DAYS, MAX_ESTIMATED_DAYS, BASE_DAYS, AFTER_DAYS)
                .stream()
                .map(PromotionReportService::promotion)
                .sorted(Comparator.comparing(Promotion::extraMargin, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Promotion::endDate))
                .toList();
        return report(from, to, lastDay, listSince, promotions);
    }

    private static PromotionReport report(LocalDate from, LocalDate to, LocalDate lastDay, LocalDate listSince,
            List<Promotion> promotions) {
        return new PromotionReport(from, to, lastDay, listSince, NO_REACTION_UPLIFT, BASE_DAYS, AFTER_DAYS,
                promotions);
    }

    static Promotion promotion(Row row) {
        int days = (int) row.getPromoDays();
        int baseDays = (int) row.getBaseDays();
        BigDecimal quantity = row.getPromoQuantity();
        BigDecimal margin = row.getPromoMargin();
        boolean hasBase = baseDays >= MIN_BASE_DAYS && row.getBaseQuantity().signum() > 0;

        BigDecimal quantityPerDay = divide(quantity, BigDecimal.valueOf(days));
        BigDecimal baseQuantityPerDay = hasBase ? divide(row.getBaseQuantity(), BigDecimal.valueOf(baseDays)) : null;
        BigDecimal baseMarginPerDay = hasBase ? divide(row.getBaseMargin(), BigDecimal.valueOf(baseDays)) : null;
        BigDecimal uplift = hasBase ? divide(quantityPerDay, baseQuantityPerDay) : null;

        BigDecimal unitMargin = divide(margin, quantity);
        BigDecimal baseUnitMargin = hasBase ? divide(row.getBaseMargin(), row.getBaseQuantity()) : null;
        BigDecimal breakEven = unitMargin != null && unitMargin.signum() > 0 && baseUnitMargin != null
                && baseUnitMargin.signum() > 0 ? divide(baseUnitMargin, unitMargin) : null;
        BigDecimal extraMargin = hasBase
                ? margin.subtract(baseMarginPerDay.multiply(BigDecimal.valueOf(days)))
                : null;

        PromotionVerdict verdict = !hasBase ? PromotionVerdict.NO_BASELINE
                : margin.signum() < 0 ? PromotionVerdict.BELOW_COST
                : extraMargin.signum() >= 0 ? PromotionVerdict.PROFITABLE
                : uplift.compareTo(NO_REACTION_UPLIFT) < 0 ? PromotionVerdict.NO_REACTION
                : PromotionVerdict.TOO_DEEP;

        int afterDays = (int) row.getAfterDays();
        BigDecimal afterRatio = hasBase && afterDays >= MIN_AFTER_DAYS
                ? divide(divide(row.getAfterQuantity(), BigDecimal.valueOf(afterDays)), baseQuantityPerDay)
                : null;
        BigDecimal usualLosses = baseDays > 0
                ? divide(row.getBaseLosses(), BigDecimal.valueOf(baseDays)).multiply(BigDecimal.valueOf(days + afterDays))
                : null;

        BigDecimal averagePrice = divide(row.getPromoGross(), quantity);
        BigDecimal promoPrice = row.getListed() ? row.getPromoPrice() : averagePrice;
        BigDecimal regularPrice = row.getRegularPrice();
        BigDecimal discount = promoPrice != null && regularPrice != null && regularPrice.signum() > 0
                ? percent(regularPrice.subtract(promoPrice), regularPrice)
                : null;

        return new Promotion(row.getItm8(), row.getEan(), row.getName(), row.getBrand(), row.getRegionCode(),
                row.getRegionName(), row.getListed(), row.getStartDate(), row.getEndDate(), days,
                money(regularPrice), money(promoPrice), round(discount, 1), row.getListMarginPercent(),
                round(quantity, 3), money(row.getPromoGross()), money(margin), round(percent(margin, row.getPromoNet()), 1),
                baseDays, round(baseQuantityPerDay, 3), money(baseMarginPerDay),
                hasBase ? round(percent(row.getBaseMargin(), row.getBaseNet()), 1) : null, round(quantityPerDay, 3),
                round(uplift, 2), round(breakEven, 2), money(extraMargin), afterDays, round(afterRatio, 2),
                money(row.getPromoLosses()), money(usualLosses), verdict);
    }

    private static BigDecimal divide(BigDecimal value, BigDecimal by) {
        return value == null || by == null || by.signum() == 0 ? null : value.divide(by, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal of) {
        BigDecimal ratio = divide(value, of);
        return ratio == null ? null : ratio.multiply(HUNDRED);
    }

    private static BigDecimal money(BigDecimal value) {
        return round(value, 2);
    }

    private static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }
}
