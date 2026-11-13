package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Topy sprzedażowe. Grupy są w kolejności kodów nomenklatury, wiersze od najlepszego. {@code rankStoreId} to sklep,
 * z którego jest ranking; {@code stores} ma go na początku, dalej porównywane sklepy. {@code brands}: marki do wyboru
 * w filtrze.
 */
public record TopSalesReport(LocalDate from, LocalDate to, TopSalesMetric metric, TopSalesLevel level, int limit,
        BigDecimal minQuantity, int maxPromotionMarginPercent, UUID rankStoreId, List<Store> stores,
        List<Group> groups, List<Brand> brands) {

    /** Marka z artykułów sklepu rankingu (po filtrze nomenklatury), wielkimi literami, z liczbą artykułów. */
    public record Brand(String name, int articleCount) {
    }

    /**
     * Sklep w raporcie. {@code articleCount} to artykuły w rankingu, {@code promotionSkipped} wypadłe przez promocję
     * z niską marżą, {@code noCostSkipped} przez koszt FIFO równy zero.
     */
    public record Store(UUID id, String number, String name, int articleCount, int promotionSkipped,
            int noCostSkipped) {
    }

    /** {@code code} pusty = bez nomenklatury, {@code null} = cały sklep. Sumy dotyczą wszystkich artykułów rankingu. */
    public record Group(String code, String name, int articleCount, BigDecimal quantity, BigDecimal saleValueNet,
            BigDecimal marginValue, BigDecimal marginPercent, List<Row> rows) {
    }

    /** {@code others}: ten sam artykuł w porównywanych sklepach, gdy tam też jest w rankingu. */
    public record Row(int rank, String itm8, String ean, String name, String brand, BigDecimal quantity,
            BigDecimal saleValueGross, BigDecimal saleValueNet, BigDecimal marginValue, BigDecimal marginPercent,
            List<OtherStore> others) {
    }

    /** {@code rank}: miejsce w tej samej pozycji nomenklatury w tamtym sklepie. */
    public record OtherStore(UUID storeId, int rank, BigDecimal quantity, BigDecimal marginValue,
            BigDecimal marginPercent) {
    }

    /** Sklep zaprzyjaźniony do porównania. */
    public record Partner(UUID id, String number, String name) {
    }
}
