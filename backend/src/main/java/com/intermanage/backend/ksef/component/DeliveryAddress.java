package com.intermanage.backend.ksef.component;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Adres dostawy z faktury porównywany z adresem sklepu. Faktury bez IDWew (np. z bazy SCA PR) wskazują sklep tylko
 * adresem w Podmiocie 3, zapisanym za każdym razem inaczej ("UL.SZOSA KISIELINSKA 22", "ul. Szosa Kisielińska 22 ,
 * 65-247 Zielona Góra, lubuskie…", "65-735 ZIELONA GÓRA / UL.BATOREGO 81"), a kody pocztowe bywają przypadkowe (Batorego
 * 81 z 65-735, 65-152 i 65-247). Dlatego porównujemy samą ulicę z numerem: ostatnie słowo nazwy ulicy i numer domu
 * ({@link #storeKey}, "kisielinska 22", "batorego 81") jako osobne słowa w znormalizowanym adresie faktury.
 */
public final class DeliveryAddress {

    private static final Pattern POSTAL_CODE = Pattern.compile("\\b\\d{2}\\s*-\\s*\\d{3}\\b");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9]+");
    /** Słowo z liter (nazwa ulicy, co najmniej 3 litery) i zaraz po nim numer domu (z literą: 81a). */
    private static final Pattern STREET_NUMBER = Pattern.compile("(?:^| )([a-z]{3,}) (\\d+[a-z]?)(?= |$)");

    private DeliveryAddress() {
    }

    /**
     * Małe litery bez polskich znaków, bez kodów pocztowych, słowa rozdzielone jedną spacją, ze spacją na początku
     * i końcu (żeby "batorego 81" nie trafiło w "batorego 815"); {@code null} przy pustym adresie.
     */
    public static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String plain = Normalizer.normalize(address.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        plain = POSTAL_CODE.matcher(plain).replaceAll(" ");
        plain = NOT_ALNUM.matcher(plain).replaceAll(" ").trim();
        return plain.isEmpty() ? null : " " + plain + " ";
    }

    /** Kilka adresów (Podmioty 3 faktury) w jednym napisie do porównania; {@code null}, gdy żadnego nie ma. */
    public static String normalizeAll(Collection<String> addresses) {
        String joined = addresses.stream().map(DeliveryAddress::normalize).filter(Objects::nonNull)
                .map(String::trim).distinct().collect(Collectors.joining(" | "));
        return joined.isEmpty() ? null : " " + joined + " ";
    }

    /**
     * Ulica z numerem z adresu sklepu ("ul. Szosa Kisielińska 22, 65-247 Zielona Góra" → "kisielinska 22"): pierwsze
     * słowo z liter, po którym stoi numer domu; {@code null}, gdy adresu nie ma albo nie ma w nim numeru.
     */
    public static String storeKey(String storeAddress) {
        String normalized = normalize(storeAddress);
        if (normalized == null) {
            return null;
        }
        Matcher matcher = STREET_NUMBER.matcher(normalized.trim());
        return matcher.find() ? matcher.group(1) + " " + matcher.group(2) : null;
    }

    /** Czy znormalizowany adres faktury ({@link #normalize}) wskazuje ulicę i numer sklepu ({@link #storeKey}). */
    public static boolean matches(String normalizedAddress, String storeKey) {
        return normalizedAddress != null && storeKey != null && normalizedAddress.contains(likeFragment(storeKey));
    }

    /** Fragment do {@code like '%…%'} w bazie: klucz sklepu jako osobne słowa. */
    public static String likeFragment(String storeKey) {
        return " " + storeKey + " ";
    }
}
