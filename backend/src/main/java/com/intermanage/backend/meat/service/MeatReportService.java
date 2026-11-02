package com.intermanage.backend.meat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.service.DataEntryLinkService;
import com.intermanage.backend.meat.dto.MeatReport;
import com.intermanage.backend.meat.repository.MeatDeliveryRepository;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.service.StoreService;

/**
 * Porównanie cen mięsa w sklepach, do których użytkownik ma dostęp: cena zakupu netto za kg z WZ i cena sprzedaży
 * brutto za kg z dnia dostawy (sprzedaż ÷ ilość ze wszystkich EAN-ów artykułu). Bez sprzedaży z dnia dostawy (jeszcze
 * niewgrana albo artykuł się nie sprzedał) i w sklepie bez zakupu jest ostatnia znana cena sprzedaży, oznaczona
 * jako zastępcza. Średnie są zwykłymi średnimi cen: ilości z WZ nie zapisujemy.
 */
@Service
public class MeatReportService {

    static final int MAX_DAYS = 366;

    private final MeatDeliveryRepository deliveries;
    private final StoreService storeService;
    private final AppUserRepository users;

    public MeatReportService(MeatDeliveryRepository deliveries, StoreService storeService, AppUserRepository users) {
        this.deliveries = deliveries;
        this.storeService = storeService;
        this.users = users;
    }

    public enum Mode {
        /** Osobny wiersz na każdy dzień dostawy. */
        SPLIT,
        /** Jeden wiersz na artykuł, średnia z okresu. */
        AVERAGE
    }

    /** {@code storeIds} puste = wszystkie dostępne sklepy; sklep bez dostępu daje 403. */
    @Transactional(readOnly = true)
    public MeatReport report(UUID userId, List<UUID> storeIds, LocalDate from, LocalDate to, Mode mode) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("Początek okresu jest po jego końcu");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw ApiException.badRequest("Wybierz okres najwyżej do roku");
        }
        List<StoreSummary> stores = stores(userId, storeIds);
        List<UUID> ids = stores.stream().map(StoreSummary::id).toList();

        Map<String, RowData> rows = new LinkedHashMap<>();
        for (MeatDeliveryRepository.ReportLine line : deliveries.findReportLines(ids, from, to)) {
            String article = line.getItm8() != null ? line.getItm8()
                    : "WZ " + DataEntryLinkService.code(line.getDocumentName());
            LocalDate date = mode == Mode.SPLIT ? line.getDeliveryDate() : null;
            String key = date == null ? article : article + "|" + date;
            RowData row = rows.computeIfAbsent(key, k -> new RowData(k, line.getItm8(),
                    line.getItm8() != null ? line.getName() : line.getDocumentName(),
                    line.getItm8() != null ? line.getBrand() : null, date));
            if (row.vatCode == null) {
                row.vatCode = line.getVatCode();
            }
            row.cell(line.getStoreId()).purchases.add(new Purchase(line.getDeliveryDate(), line.getUnitPrice()));
        }

        SalePrices prices = salePrices(rows.values(), ids, to);
        List<MeatReport.Row> result = rows.values().stream()
                .sorted(Comparator.comparing((RowData row) -> row.brand == null ? "￿" : row.brand)
                        .thenComparing(row -> Objects.toString(row.name, ""))
                        .thenComparing(row -> row.date, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(row -> new MeatReport.Row(row.key, row.itm8, row.name, row.brand, row.date,
                        vatPercent(row.vatCode),
                        ids.stream().map(storeId -> cell(row, storeId, prices)).toList()))
                .toList();
        return new MeatReport(from, to, mode.name(),
                stores.stream().map(store -> new MeatReport.Store(store.id(), store.number(), store.name())).toList(),
                result);
    }

    private List<StoreSummary> stores(UUID userId, List<UUID> storeIds) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.forbidden("Brak dostępu"));
        List<StoreSummary> accessible = storeService.accessibleStores(user);
        if (storeIds == null || storeIds.isEmpty()) {
            return accessible;
        }
        Set<UUID> wanted = Set.copyOf(storeIds);
        List<StoreSummary> selected = accessible.stream().filter(store -> wanted.contains(store.id())).toList();
        if (selected.size() != wanted.size()) {
            throw ApiException.forbidden("Brak dostępu do wybranego sklepu");
        }
        return selected;
    }

    private MeatReport.Cell cell(RowData row, UUID storeId, SalePrices prices) {
        CellData data = row.cells.get(storeId);
        List<Purchase> purchases = data == null ? List.of() : data.purchases;
        BigDecimal purchase = average(purchases.stream().map(Purchase::price).toList());
        if (row.itm8 == null) {
            return new MeatReport.Cell(storeId, purchase, purchases.size(), null, null, false);
        }
        if (purchases.isEmpty()) {
            SalePrice last = prices.at(storeId, row.itm8, prices.lastDay(row));
            return new MeatReport.Cell(storeId, null, 0, last == null ? null : last.price(),
                    last == null ? null : last.date(), true);
        }
        List<BigDecimal> exact = new ArrayList<>();
        SalePrice latest = null;
        for (LocalDate day : new TreeSet<>(purchases.stream().map(Purchase::date).toList())) {
            SalePrice price = prices.at(storeId, row.itm8, day);
            if (price == null) {
                continue;
            }
            if (price.date().equals(day)) {
                exact.add(price.price());
            } else if (latest == null || price.date().isAfter(latest.date())) {
                latest = price;
            }
        }
        if (!exact.isEmpty()) {
            return new MeatReport.Cell(storeId, purchase, purchases.size(), average(exact), null, false);
        }
        return new MeatReport.Cell(storeId, purchase, purchases.size(), latest == null ? null : latest.price(),
                latest == null ? null : latest.date(), true);
    }

    /** Ceny sprzedaży potrzebne w raporcie, jednym zapytaniem: dni dostaw i dzień, na który bierzemy ostatnią znaną. */
    private SalePrices salePrices(Iterable<RowData> rows, List<UUID> storeIds, LocalDate to) {
        SalePrices prices = new SalePrices(to);
        List<UUID> stores = new ArrayList<>();
        List<String> itm8s = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (RowData row : rows) {
            if (row.itm8 == null) {
                continue;
            }
            for (UUID storeId : storeIds) {
                CellData data = row.cells.get(storeId);
                List<LocalDate> wanted = data == null ? List.of(prices.lastDay(row))
                        : data.purchases.stream().map(Purchase::date).distinct().toList();
                for (LocalDate day : wanted) {
                    if (seen.add(SalePrices.key(storeId, row.itm8, day))) {
                        stores.add(storeId);
                        itm8s.add(row.itm8);
                        days.add(day);
                    }
                }
            }
        }
        if (stores.isEmpty()) {
            return prices;
        }
        for (MeatDeliveryRepository.SalePrice price : deliveries.findSalePrices(stores.toArray(UUID[]::new),
                itm8s.toArray(String[]::new), days.toArray(LocalDate[]::new))) {
            int i = price.getIdx() - 1;
            prices.put(stores.get(i), itm8s.get(i), days.get(i), new SalePrice(price.getSaleDate(), price.getPrice()));
        }
        return prices;
    }

    /** Stawka VAT z kodu z kartoteki (1 = 23%, 2 = 8%, 6 = 5%); mięso bez kodu ma 5%. */
    static BigDecimal vatPercent(Short vatCode) {
        if (vatCode == null) {
            return BigDecimal.valueOf(5);
        }
        return switch (vatCode) {
            case 1 -> BigDecimal.valueOf(23);
            case 2 -> BigDecimal.valueOf(8);
            default -> BigDecimal.valueOf(5);
        };
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private record Purchase(LocalDate date, BigDecimal price) {
    }

    private record SalePrice(LocalDate date, BigDecimal price) {
    }

    private static final class SalePrices {
        private final Map<String, SalePrice> prices = new HashMap<>();
        private final LocalDate to;

        SalePrices(LocalDate to) {
            this.to = to;
        }

        /** Dzień, na który sklep bez zakupu dostaje ostatnią znaną cenę: dzień wiersza albo koniec okresu. */
        LocalDate lastDay(RowData row) {
            return row.date != null ? row.date : to;
        }

        static String key(UUID storeId, String itm8, LocalDate day) {
            return storeId + "|" + itm8 + "|" + day;
        }

        void put(UUID storeId, String itm8, LocalDate day, SalePrice price) {
            prices.put(key(storeId, itm8, day), price);
        }

        SalePrice at(UUID storeId, String itm8, LocalDate day) {
            return prices.get(key(storeId, itm8, day));
        }
    }

    private static final class RowData {
        final String key;
        final String itm8;
        final String name;
        final String brand;
        final LocalDate date;
        Short vatCode;
        final Map<UUID, CellData> cells = new HashMap<>();

        RowData(String key, String itm8, String name, String brand, LocalDate date) {
            this.key = key;
            this.itm8 = itm8;
            this.name = name;
            this.brand = brand;
            this.date = date;
        }

        CellData cell(UUID storeId) {
            return cells.computeIfAbsent(storeId, id -> new CellData());
        }
    }

    private static final class CellData {
        final List<Purchase> purchases = new ArrayList<>();
    }
}
