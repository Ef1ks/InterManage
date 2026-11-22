package com.intermanage.backend.mea.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.dto.MeaArticleSales;
import com.intermanage.backend.mea.repository.MeaDocumentRepository;
import com.intermanage.backend.mea.repository.MeaSalesRepository;
import com.intermanage.backend.promotion.service.PromotionReportService;
import com.intermanage.backend.sales.repository.SaleRepository;

/**
 * Sprzedaż artykułów dokumentu MEA w ostatnich tygodniach czwartek-środa (tydzień gazetkowy zaczyna się w czwartek)
 * z promocjami, żeby przy zamawianiu było widać, ile artykuł sprzedaje zwykle, a ile w promocji.
 */
@Service
public class MeaSalesService {

    public static final int DEFAULT_WEEKS = 8;
    static final int MAX_WEEKS = 26;
    static final int MAX_ARTICLES = 300;

    private final MeaSalesRepository repository;
    private final MeaDocumentRepository documents;
    private final SaleRepository sales;

    public MeaSalesService(MeaSalesRepository repository, MeaDocumentRepository documents, SaleRepository sales) {
        this.repository = repository;
        this.documents = documents;
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public MeaArticleSales sales(UUID storeId, List<String> codes, int weeks) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw ApiException.badRequest("Liczba tygodni od 1 do " + MAX_WEEKS);
        }
        Set<String> itm8s = new LinkedHashSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            String digits = code == null ? "" : code.strip();
            if (digits.matches("\\d{1,13}")) {
                itm8s.add("0".repeat(13 - digits.length()) + digits);
            }
        }
        if (itm8s.size() > MAX_ARTICLES) {
            throw ApiException.badRequest("Najwyżej " + MAX_ARTICLES + " artykułów naraz");
        }

        LocalDate yesterday = LocalDate.now(StoreTime.ZONE).minusDays(1);
        LocalDate lastSale = repository.findLastSaleDate(storeId);
        LocalDate last = lastSale == null || lastSale.isAfter(yesterday) ? yesterday : lastSale;
        // Ostatni tydzień kończy się w środę: ta z ostatnim dniem sprzedaży (tydzień w toku) albo ten dzień.
        int toWednesday = (DayOfWeek.WEDNESDAY.getValue() - last.getDayOfWeek().getValue() + 7) % 7;
        LocalDate to = last.plusDays(toWednesday);
        LocalDate from = to.minusDays(7L * weeks - 1);
        if (itm8s.isEmpty()) {
            return new MeaArticleSales(from, to, lastSale, List.of());
        }

        sales.tuneForReport();
        Map<String, List<MeaArticleSales.Day>> days = new LinkedHashMap<>();
        Map<String, List<MeaArticleSales.Promo>> promos = new LinkedHashMap<>();
        repository.findDays(storeId, itm8s, from, to, from.withDayOfMonth(1).minusMonths(2),
                PromotionReportService.PRICE_FACTOR, PromotionReportService.MIN_REFERENCE_DAYS)
                .forEach(row -> days.computeIfAbsent(row.getItm8(), key -> new ArrayList<>())
                        .add(new MeaArticleSales.Day(row.getSaleDate(), row.getQuantity(), row.getGross(),
                                row.getPriceDrop())));
        repository.findPromotions(storeId, itm8s, from, to)
                .forEach(row -> promos.computeIfAbsent(row.getItm8(), key -> new ArrayList<>())
                        .add(new MeaArticleSales.Promo(row.getSource(), null, row.getDateFrom(), row.getDateTo())));
        documents.findArticleLeaflets(storeId, itm8s.toArray(String[]::new), from, to)
                .forEach(row -> promos.computeIfAbsent(row.getItm8(), key -> new ArrayList<>())
                        .add(new MeaArticleSales.Promo(row.getNumber() == null ? "LEAFLET" : "MEA",
                                row.getNumber(), row.getPromoFrom(), row.getPromoTo())));

        List<MeaArticleSales.Article> articles = itm8s.stream()
                .map(itm8 -> new MeaArticleSales.Article(itm8, days.getOrDefault(itm8, List.of()),
                        promos.getOrDefault(itm8, List.of())))
                .toList();
        return new MeaArticleSales(from, to, lastSale, articles);
    }
}
