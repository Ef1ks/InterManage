package com.intermanage.backend.sales.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

import com.intermanage.backend.sales.dto.ArticleListReport.Item;

/**
 * Kolumny listy artykułów: nagłówek, szerokość na wydruku (względna) i w Excelu (znaki), rodzaj wartości. Nazwa stałej
 * jest w API (wybór kolumn, sortowanie), te same nazwy ma front.
 */
public enum ArticleListColumn {
    ITM8("ITM8", 44, 10, Kind.TEXT, item -> item.itm8().replaceFirst("^0+", "")),
    EAN("EAN", 66, 15, Kind.TEXT, Item::ean),
    NAME("Nazwa", 160, 34, Kind.TEXT, Item::name),
    BRAND("Marka", 70, 18, Kind.TEXT, Item::brand),
    NOMENCLATURE("Nomenklatura", 48, 10, Kind.TEXT, ArticleListColumn::nomenclature),
    REGION("Rejon", 80, 22, Kind.TEXT, item -> named(item.regionCode(), item.regionName())),
    FAMILY("Rodzina", 90, 24, Kind.TEXT, item -> named(item.familyCode(), item.familyName())),
    SUBFAMILY("Podrodzina", 100, 26, Kind.TEXT, item -> named(item.subfamilyCode(), item.subfamilyName())),
    CATALOG_SUPPLIER("Dostawca (kartoteka)", 90, 24, Kind.TEXT, Item::catalogSupplier),
    LAST_SUPPLIER("Ostatni dostawca", 100, 26, Kind.TEXT, ArticleListColumn::lastSupplier),
    LAST_DELIVERY("Ostatnia dostawa", 50, 12, Kind.DATE, Item::lastDeliveryDate),
    STOCK("Stan", 40, 10, Kind.QUANTITY, Item::stock),
    ROTATION("Rotacja", 40, 10, Kind.QUANTITY, Item::rotation),
    PURCHASE_PRICE("Cena zakupu netto", 50, 12, Kind.MONEY, Item::purchasePrice),
    SALE_PRICE("Cena sprzedaży brutto", 50, 12, Kind.MONEY, Item::salePrice),
    VAT("VAT %", 30, 7, Kind.QUANTITY, item -> item.vatRate() == null ? null : BigDecimal.valueOf(item.vatRate())),
    MARGIN("Marża %", 38, 9, Kind.PERCENT, Item::marginPercent),
    STOCK_VALUE("Wartość stanu (zakup)", 56, 14, Kind.MONEY, Item::stockValue),
    STOCK_SALE_VALUE("Wartość stanu (sprzedaż)", 56, 14, Kind.MONEY, Item::stockSaleValue),
    UNIT("Jedn.", 30, 7, Kind.TEXT, Item::unit),
    NET_CONTENT("Zawartość", 38, 9, Kind.QUANTITY, Item::netContent),
    CASE_SIZE("Karton", 34, 8, Kind.QUANTITY, Item::caseSize),
    KIND("Rodzaj", 40, 10, Kind.TEXT, Item::kind),
    CADENCIER("Cadencier", 40, 10, Kind.TEXT, item -> item.cadencier() ? "tak" : null),
    CADENCIER_PRICE("CENA_CAD", 44, 10, Kind.MONEY, Item::cadencierPrice),
    PROMO_PRICE("Promocja z kasy", 46, 11, Kind.MONEY, Item::promoPrice),
    PROMO_TO("Promocja do", 46, 11, Kind.DATE, Item::promoTo),
    ACTIVE("Aktywny", 38, 9, Kind.TEXT, item -> item.active() ? "tak" : "nie"),
    DEPOSIT("Kaucja", 34, 8, Kind.TEXT, item -> item.depositCode() == null || item.depositCode() == 0
            || item.depositCode() == 99 ? null : String.valueOf(item.depositCode())),
    DLV("DLV", 30, 7, Kind.QUANTITY, item -> item.dlv() == null ? null : BigDecimal.valueOf(item.dlv())),
    CN("Kod CN", 50, 11, Kind.TEXT, Item::cnCode);

    /** Jak pokazać wartość: tekst, ilość, kwota z dwoma miejscami, procent, data. */
    public enum Kind {
        TEXT, QUANTITY, MONEY, PERCENT, DATE
    }

    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH);

    private final String label;
    private final float pdfWidth;
    private final int xlsxWidth;
    private final Kind kind;
    private final Function<Item, Object> value;

    ArticleListColumn(String label, float pdfWidth, int xlsxWidth, Kind kind, Function<Item, Object> value) {
        this.label = label;
        this.pdfWidth = pdfWidth;
        this.xlsxWidth = xlsxWidth;
        this.kind = kind;
        this.value = value;
    }

    public String label() {
        return label;
    }

    public float pdfWidth() {
        return pdfWidth;
    }

    public int xlsxWidth() {
        return xlsxWidth;
    }

    public Kind kind() {
        return kind;
    }

    public boolean numeric() {
        return kind != Kind.TEXT && kind != Kind.DATE;
    }

    /** Surowa wartość: tekst, {@link BigDecimal} albo {@link LocalDate}; {@code null} = pusto. */
    public Object value(Item item) {
        return value.apply(item);
    }

    /** Wartość do porównania przy sortowaniu: teksty bez wielkości liter. */
    @SuppressWarnings("unchecked")
    public Comparable<Object> sortKey(Item item) {
        Object raw = value(item);
        if (raw instanceof String text) {
            return (Comparable<Object>) (Comparable<?>) text.toLowerCase(POLISH);
        }
        return (Comparable<Object>) raw;
    }

    /** Wartość jak na wydruku. */
    public String format(Item item) {
        Object raw = value(item);
        if (raw == null) {
            return "";
        }
        return switch (kind) {
            case TEXT -> raw.toString();
            case DATE -> DATE_FORMAT.format((LocalDate) raw);
            case MONEY -> money((BigDecimal) raw);
            case PERCENT -> ((BigDecimal) raw).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            case QUANTITY -> quantity((BigDecimal) raw);
        };
    }

    public static String money(BigDecimal value) {
        return value == null ? "" : new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(POLISH)).format(value);
    }

    public static String quantity(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String nomenclature(Item item) {
        String code = item.subfamilyCode();
        if (code == null || code.length() < 6) {
            return code;
        }
        return code.substring(0, 2) + " " + code.substring(2, 4) + " " + code.substring(4, 6);
    }

    private static String named(String code, String name) {
        if (code == null) {
            return null;
        }
        String own = code.length() > 2 ? code.substring(code.length() - 2) : code;
        return name == null ? own : own + " " + name;
    }

    private static String lastSupplier(Item item) {
        if (item.lastSupplierCode() == null) {
            return null;
        }
        return item.lastSupplierName() == null ? item.lastSupplierCode()
                : item.lastSupplierCode() + " " + item.lastSupplierName();
    }
}
