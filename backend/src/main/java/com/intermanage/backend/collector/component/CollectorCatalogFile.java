package com.intermanage.backend.collector.component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plik kartoteki kolektora (czyta go {@code collector/src/Catalog.cs}; zmiana układu = zmiana w obu i nowy
 * {@link #MAGIC}). Kolektor ma wolny procesor i mało pamięci, więc serwer układa indeksy, a kolektor tylko w nich
 * szuka. Little endian:
 *
 * <pre>
 * 8 bajtów  IMKATAL2
 * int32     długość nagłówka, potem nagłówek UTF-8 (klucz=wartość w liniach)
 * int32 × 5 liczba artykułów n, bajty wierszy, bajty tekstu do szukania, liczba EAN-ów e, bajty nomenklatury
 * int32 × (n + 1)  początki wierszy
 * wiersze UTF-8 (pola oddzielone tabulatorem, {@link #FIELDS})
 * teksty do szukania ASCII (nazwa i marka wielkimi literami bez polskich znaków, zakończone LF;
 *                  numer linii = numer artykułu)
 * int64 × e EAN-y rosnąco (bez zer wiodących)   int32 × e numery artykułów
 * int32 × e licznik i int32 × e mianownik przelicznika: ile jednostek głównego kodu to sztuka tego kodu (puszka 1/4)
 * int64 × n ITM8 rosnąco                        int32 × n numery artykułów
 * nomenklatura UTF-8: kod, tabulator, nazwa, LF
 * </pre>
 */
public final class CollectorCatalogFile {

    public static final String MAGIC = "IMKATAL2";

    /**
     * Kolejność pól wiersza; to samo w {@code Catalog.cs}. Nowe pola dochodzą tylko na końcu: starszy program czyta
     * pierwsze, których się spodziewa, więc plik nie potrzebuje nowego {@link #MAGIC}.
     * <ul>
     * <li>{@code deposit}: kaucja głównego EAN-u (kod kaucji z kartoteki) jako {@code wartość;nazwa}, pusta = bez;</li>
     * <li>{@code codes}: inne kody z multikodów do cenówki, rozdzielone {@code |}, każdy
     * {@code EAN;mnożnik ceny;wartość kaucji;nazwa kaucji;nazwa kodu} (cena kodu = cena artykułu × mnożnik, np. puszka
     * z czteropaka 0,25).</li>
     * <li>{@code cadencierPurchase}: cena zakupu netto sztuki z dzisiejszego pliku TOP, tylko artykułu aktywnego
     * w cadencier (od programu 1.8.3, okno ilości w protokole); pusta = poza cadencier.</li>
     * </ul>
     */
    public static final List<String> FIELDS = List.of("itm8", "ean", "name", "brand", "subfamily", "salePrice",
            "promoPrice", "promoFrom", "promoTo", "promoRegular", "stock", "rotation", "caseSize", "unit", "netContent",
            "cadencierPrice", "active", "cadencier", "supplier", "lastDelivery", "eans", "multipack", "deposit",
            "codes", "cadencierPurchase");

    /**
     * Artykuł: wartości pól w kolejności {@link #FIELDS}, kody od najpewniejszych (główny, multikody, dawne pary) i
     * opakowania sprzedaży multikodów (EAN jako liczba → opakowanie).
     */
    public record Article(List<String> fields, String itm8, String name, String brand, String mainEan,
            Collection<String> codes, Collection<String> formerCodes, Map<Long, BigDecimal> packs) {
    }

    /** Przelicznik: ilość głównego kodu = wpisana ilość × numerator / denominator. */
    public record Ratio(int numerator, int denominator) {

        static final Ratio ONE = new Ratio(1, 1);
        /** Opakowanie w raporcie ma 3 miejsca, ucięte (1/6 = 0,166), więc ułamki 1/n rozpoznajemy z taką dokładnością. */
        private static final BigDecimal TOLERANCE = new BigDecimal("0.001");
        private static final int MAX_PIECES = 48;

        /** Z opakowań zeskanowanego i głównego kodu; nieznane albo równe = 1. */
        /** Kod to jedna sztuka wielopaku: 1/4 z czteropaka. */
        public boolean isPiece() {
            return numerator == 1 && denominator > 1 && denominator <= MAX_PIECES;
        }

        public static Ratio of(BigDecimal scanned, BigDecimal main) {
            if (scanned == null || scanned.signum() <= 0 || main == null || main.signum() <= 0
                    || scanned.compareTo(main) == 0) {
                return ONE;
            }
            BigInteger[] a = fraction(scanned);
            BigInteger[] b = fraction(main);
            BigInteger numerator = a[0].multiply(b[1]);
            BigInteger denominator = a[1].multiply(b[0]);
            BigInteger divisor = numerator.gcd(denominator);
            return new Ratio(numerator.divide(divisor).intValueExact(), denominator.divide(divisor).intValueExact());
        }

        private static BigInteger[] fraction(BigDecimal pack) {
            if (pack.compareTo(BigDecimal.ONE) < 0) {
                int pieces = BigDecimal.ONE.divide(pack, 0, RoundingMode.HALF_UP).intValue();
                BigDecimal exact = BigDecimal.ONE.divide(BigDecimal.valueOf(pieces), 9, RoundingMode.HALF_UP);
                if (pieces <= MAX_PIECES && exact.subtract(pack).abs().compareTo(TOLERANCE) < 0) {
                    return new BigInteger[] {BigInteger.ONE, BigInteger.valueOf(pieces)};
                }
            }
            return new BigInteger[] {pack.setScale(3, RoundingMode.HALF_UP).movePointRight(3).toBigIntegerExact(),
                BigInteger.valueOf(1000)};
        }
    }

    private CollectorCatalogFile() {
    }

    public static byte[] write(Map<String, String> header, List<Article> articles, Map<String, String> nomenclature) {
        byte[] headerBytes = header.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + clean(entry.getValue()))
                .collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8);
        int count = articles.size();
        int[] rowStarts = new int[count + 1];
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        ByteArrayOutputStream search = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            Article article = articles.get(i);
            if (article.fields().size() != FIELDS.size()) {
                throw new IllegalArgumentException("Wiersz ma inną liczbę pól niż plik kolektora");
            }
            rowStarts[i] = rows.size();
            rows.writeBytes(article.fields().stream().map(CollectorCatalogFile::clean)
                    .collect(Collectors.joining("\t")).getBytes(StandardCharsets.UTF_8));
            search.writeBytes((searchText(article.name()) + " " + searchText(article.brand()) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        rowStarts[count] = rows.size();

        List<long[]> eans = eanIndex(articles);
        List<long[]> itm8s = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            itm8s.add(new long[] {number(articles.get(i).itm8()), i});
        }
        sort(itm8s);

        byte[] names = nomenclature.entrySet().stream()
                .map(entry -> entry.getKey() + "\t" + clean(entry.getValue()) + "\n")
                .collect(Collectors.joining()).getBytes(StandardCharsets.UTF_8);

        long size = 8L + 4 + headerBytes.length + 20 + 4L * (count + 1) + rows.size() + search.size()
                + 20L * eans.size() + 12L * count + names.length;
        ByteBuffer out = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        out.putInt(headerBytes.length).put(headerBytes);
        out.putInt(count).putInt(rows.size()).putInt(search.size()).putInt(eans.size()).putInt(names.length);
        for (int start : rowStarts) {
            out.putInt(start);
        }
        out.put(rows.toByteArray()).put(search.toByteArray());
        putIndex(out, eans);
        for (long[] entry : eans) {
            out.putInt((int) entry[2]);
        }
        for (long[] entry : eans) {
            out.putInt((int) entry[3]);
        }
        putIndex(out, itm8s);
        out.put(names);
        return out.array();
    }

    /**
     * EAN → artykuły. Główny EAN ma pierwszeństwo przed multikodem, a multikod przed dawną parą (jak we wprowadzaniu
     * danych); EAN kilku artykułów na tym samym poziomie prowadzi do każdego z nich.
     */
    private static List<long[]> eanIndex(List<Article> articles) {
        Map<Long, Integer> ranks = new HashMap<>();
        Map<Long, Set<Integer>> owners = new HashMap<>();
        for (int rank = 0; rank < 3; rank++) {
            for (int i = 0; i < articles.size(); i++) {
                Article article = articles.get(i);
                Collection<String> codes = rank == 0 ? List.of(article.mainEan() == null ? "" : article.mainEan())
                        : rank == 1 ? article.codes() : article.formerCodes();
                for (String code : codes) {
                    long key = number(code);
                    if (key <= 0) {
                        continue;
                    }
                    if (ranks.computeIfAbsent(key, ignored -> Integer.MAX_VALUE) < rank) {
                        continue;
                    }
                    ranks.put(key, rank);
                    owners.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(i);
                }
            }
        }
        List<long[]> result = new ArrayList<>(owners.size());
        owners.forEach((key, indexes) -> indexes.forEach(index -> {
            Article article = articles.get(index);
            Ratio ratio = Ratio.of(article.packs().get(key), article.packs().get(number(article.mainEan())));
            result.add(new long[] {key, index, ratio.numerator(), ratio.denominator()});
        }));
        sort(result);
        return result;
    }

    private static void sort(List<long[]> index) {
        index.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
    }

    private static void putIndex(ByteBuffer out, List<long[]> index) {
        for (long[] entry : index) {
            out.putLong(entry[0]);
        }
        for (long[] entry : index) {
            out.putInt((int) entry[1]);
        }
    }

    /** Kod z cyfr jako liczba (bez zer wiodących), inaczej 0. */
    public static long number(String code) {
        String digits = code == null ? "" : code.strip();
        if (digits.isEmpty() || digits.length() > 18) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char digit = digits.charAt(i);
            if (digit < '0' || digit > '9') {
                return 0;
            }
            value = value * 10 + (digit - '0');
        }
        return value;
    }

    /** Tekst do szukania: wielkie litery bez polskich znaków, inne znaki spoza ASCII jako „?”. */
    static String searchText(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.toUpperCase(Locale.ROOT).replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.replaceAll("[\\s]+", " ").replaceAll("[^ -~]", "?").strip();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }
}
