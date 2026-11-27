package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Filtry listy artykułów (parametry zapytania), wszystkie opcjonalne.
 * <ul>
 * <li>{@code query}: każde słowo w nazwie, marce, EAN-ie albo ITM8; {@code nomenclatures}: kody rejonów, rodzin albo
 * podrodzin; {@code withoutNomenclature}: tylko artykuły bez podrodziny; {@code category}: id kategorii własnej albo
 * OTHER.</li>
 * <li>{@code brands}: marki (bez wielkości liter); {@code suppliers}: kody ostatniego dostawcy z przyjęć PZ;
 * {@code catalogSupplier}: fragment dostawcy z kartoteki; {@code units}, {@code kinds}, {@code vatRates}: wartości z
 * listy.</li>
 * <li>{@code stock}: POSITIVE, ZERO, NEGATIVE; zakresy od-do (włącznie) stanu, rotacji, ceny zakupu, ceny sprzedaży,
 * marży %, wartości stanu.</li>
 * <li>{@code cadencier}, {@code promotion}, {@code deposit}: WITH albo WITHOUT; {@code active}: ACTIVE albo INACTIVE;
 * {@code delivery}: WITH (była dostawa) albo WITHOUT (nigdy); {@code deliveredFrom}, {@code deliveredTo}: ostatnia
 * dostawa w tych dniach.</li>
 * <li>{@code allArticles}: także artykuły spoza najnowszego raportu kartoteki; {@code withNotGoods}: także rejony,
 * które nie są towarem (torby, opakowania zwrotne).</li>
 * <li>{@code groupBy}: NONE, REGION, FAMILY, SUBFAMILY; {@code sort} (nazwa kolumny {@code ArticleListColumn}) i
 * {@code direction}; {@code columns}: kolumny wydruku i Excela w tej kolejności; {@code articles}: false = na wydruku
 * same podsumowania grup.</li>
 * </ul>
 */
public record ArticleListRequest(String query, List<String> nomenclatures, Boolean withoutNomenclature,
        String category, List<String> brands, List<String> suppliers, String catalogSupplier, List<String> units,
        List<String> kinds, List<Integer> vatRates, String stock, BigDecimal stockFrom, BigDecimal stockTo,
        BigDecimal rotationFrom, BigDecimal rotationTo, BigDecimal purchaseFrom, BigDecimal purchaseTo,
        BigDecimal saleFrom, BigDecimal saleTo, BigDecimal marginFrom, BigDecimal marginTo, BigDecimal valueFrom,
        BigDecimal valueTo, String cadencier, String promotion, String deposit, String active, String delivery,
        LocalDate deliveredFrom, LocalDate deliveredTo, Boolean allArticles, Boolean withNotGoods, String groupBy,
        String sort, String direction, List<String> columns, Boolean articles) {
}
