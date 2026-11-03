package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.intermanage.backend.common.component.XlsWriter;
import com.intermanage.backend.dataentry.dto.ProductFileRequest;

/**
 * Plik nowych i zmienionych artykułów (CADBEZP, .xls) dla programu sklepu, w układzie z pliku wzorcowego
 * {@code CADBEZP-IMPEK.xls}: 25 kolumn: 19 kolumn danych, 5 pustych (bez nagłówków) i CN (z pozycji faktury, bez niego {@value #CN}). Ref. dostawcy to EAN. Opakowanie i zamówienie minimalne 1, rabat
 * 0, cena po rabacie = cena zakupu, cena sugerowana = cena sprzedaży, PKWIU 0. Marka ma najwyżej
 * {@value #BRAND_LIMIT} znaków jak we wzorze („DR PEPPE”).
 */
public final class ProductFile {

    public static final String CN = "99999980";
    static final int BRAND_LIMIT = 8;

    public static final List<String> HEADERS = List.of("Ref.Dostawct", "Nazwa", "Nazwa Handl.", "Kod EAN13",
            "Opak.Bazy", "Zamówienie minimalne", "Jednostka miary", "Masa/Objętość", "Marka", "CZ", "Rabat",
            "CZ po rabacie", "VAT", "CS", "CS sugerowana", "PKWIU", "Rejon", "Rodzina", "Pod - rodzina",
            "Segment", "", "", "", "", "CN");

    private ProductFile() {
    }

    /**
     * Wiersze 1:1 jak w pliku CADBEZP z Impeka, który program sklepu wczytuje; Segment jak u nich: 0 w pierwszym
     * wierszu, w kolejnych pusty tekst.
     */
    public static List<List<Object>> rows(List<ProductFileRequest.Row> rows) {
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(i -> row(rows.get(i), i == 0 ? (Object) BigDecimal.ZERO : XlsWriter.EMPTY_TEXT)).toList();
    }

    private static List<Object> row(ProductFileRequest.Row row, Object segment) {
        String subfamily = row.subfamilyCode() != null ? row.subfamilyCode().strip() : "";
        BigDecimal purchase = money(row.purchasePrice());
        BigDecimal sale = money(row.salePrice());
        BigDecimal pack = pack(row.pack());
        List<Object> cells = new ArrayList<>(Arrays.asList(
                ean(row.ean()), text(row.name()), text(row.name()), ean(row.ean()), pack, pack, unit(row.unit()),
                row.netContent(), brand(row.brand()), purchase, BigDecimal.ZERO, purchase, vat(row.vatPercent()),
                sale, sale, BigDecimal.ZERO, level(subfamily, 0), level(subfamily, 2), level(subfamily, 4)));
        cells.addAll(Arrays.asList(segment, null, null, null, null, cn(row.cn())));
        return cells;
    }

    /**
     * Opak.Bazy i Zamówienie minimalne: sztuk w kartonie z dostawy z bazy, bez niego (faktura) 1. Liczba, nie tekst,
     * jak w pliku CADBEZP z Impeka, który program sklepu wczytuje.
     */
    private static BigDecimal pack(BigDecimal pack) {
        return pack != null && pack.signum() > 0 ? pack.stripTrailingZeros() : BigDecimal.ONE;
    }

    /** Kod CN z pozycji faktury (same cyfry), bez niego {@value #CN}. */
    private static String cn(String cn) {
        String digits = cn == null ? "" : cn.replaceAll("\\s", "");
        return digits.matches("\\d{2,10}") ? digits : CN;
    }

    /** Tylko kod z cyfr; kod zastępczy (nazwa, indeks) to brak EAN-u. */
    private static String ean(String ean) {
        return ean != null && ean.strip().matches("\\d{8,14}") ? ean.strip() : null;
    }

    /** Jednostka programu sklepu: P sztuka, K kilogram, L litr. */
    static String unit(String unit) {
        if (unit == null) {
            return "P";
        }
        return switch (unit.strip().toLowerCase(Locale.ROOT)) {
            case "kg" -> "K";
            case "l" -> "L";
            default -> "P";
        };
    }

    static String brand(String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        String plain = plain(brand);
        return plain.length() > BRAND_LIMIT ? plain.substring(0, BRAND_LIMIT).strip() : plain;
    }

    private static String vat(BigDecimal percent) {
        return percent == null ? null : percent.stripTrailingZeros().toPlainString() + "%";
    }

    private static String level(String subfamily, int from) {
        return subfamily.length() >= from + 2 ? subfamily.substring(from, from + 2) : null;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Nazwa pliku: CADBEZP-<dostawca>[-<nr faktury>].xls, bez znaków niedozwolonych w nazwach plików. */
    public static String fileName(String supplierName, String invoiceNumber) {
        String supplier = supplierPart(supplierName);
        StringBuilder name = new StringBuilder("CADBEZP");
        if (!supplier.isEmpty()) {
            name.append('-').append(supplier);
        }
        if (invoiceNumber != null && !invoiceNumber.isBlank()) {
            name.append('-').append(invoiceNumber.strip());
        }
        return name.toString().replaceAll("[\\\\/:*?\"<>|()\\p{Cntrl}\\s]+", "_").replaceAll("_+", "_") + ".xls";
    }

    /** Wielkie litery bez polskich znaków. */
    /** Nazwa dostawcy w nazwie pliku: wielkie litery bez polskich znaków, najwyżej 40 znaków; pusta bez nazwy. */
    public static String supplierPart(String supplierName) {
        String supplier = supplierName == null ? "" : plain(supplierName);
        return supplier.length() > 40 ? supplier.substring(0, 40).strip() : supplier;
    }

    private static String plain(String text) {
        return Normalizer.normalize(text.strip().toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('Ł', 'L');
    }
}
