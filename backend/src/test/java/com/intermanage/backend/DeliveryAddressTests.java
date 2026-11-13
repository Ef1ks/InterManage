package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.ksef.component.DeliveryAddress;

/** Adresy dostawy z Podmiotów 3 prawdziwych faktur (produkcja, firma 7812022538) wobec adresów sklepów. */
class DeliveryAddressTests {

    private static final String KISIELINSKA = DeliveryAddress.storeKey("ul. Szosa Kisielińska 22, 65-247 Zielona Góra");
    private static final String MAKOWA = DeliveryAddress.storeKey("ul. Makowa 14, 65-382 Zielona Góra");
    private static final String BATOREGO = DeliveryAddress.storeKey("ul. Stefana Batorego 81, 65-735 Zielona Góra");

    @Test
    void storeKeyIsStreetWithNumber() {
        assertThat(KISIELINSKA).isEqualTo("kisielinska 22");
        assertThat(MAKOWA).isEqualTo("makowa 14");
        assertThat(BATOREGO).isEqualTo("batorego 81");
        // Kod pocztowy przed ulicą nie jest numerem domu.
        assertThat(DeliveryAddress.storeKey("65-247 Zielona Góra, ul. Szosa Kisielińska 22")).isEqualTo(KISIELINSKA);
        assertThat(DeliveryAddress.storeKey("Zielona Góra")).isNull();
        assertThat(DeliveryAddress.storeKey(null)).isNull();
    }

    @Test
    void invoiceAddressesWrittenDifferentlyMatchTheirStore() {
        for (String address : List.of("UL.SZOSA KISIELINSKA 22, 65-247 ZIELONA GORA",
                "ul. Szosa Kisielińska 22 , 65-247 Zielona Góra, lubuskie, gmina Zielona Góra, powiat Zielona Góra",
                "65-247 Zielona Góra, ul. Szosa Kisielińska 22", "ul. SZOSA KISIELIŃSKA 22, 65-241 ZIELONA GÓRA",
                "65-247 Zielona Góra\r\nSzosa Kisielińska 22")) {
            assertThat(only(address)).as(address).isEqualTo(KISIELINSKA);
        }
        for (String address : List.of("MAKOWA 14, 65-001 ZIELONA GÓRA", "ul. MAKOWA 14, ZIELONA GÓRA",
                "Makowa 14, 65-012 Zielona Góra, lubuskie")) {
            assertThat(only(address)).as(address).isEqualTo(MAKOWA);
        }
        // Batorego z przypadkowymi kodami pocztowymi, w tym 65-247 jak Kisielińska.
        for (String address : List.of("65-735 ZIELONA GÓRA, UL.BATOREGO 81", "ul.Batorego 81, 65-152 Zielona Góra",
                "Stefana Batorego  81, 65-735 Zielona Góra", "ul. Stefana Batorego 81, 65-247 Zielona Góra",
                "ul. Batorego  81, 65-735 Zielona Góra")) {
            assertThat(only(address)).as(address).isEqualTo(BATOREGO);
        }
    }

    @Test
    void otherNumberOrNoAddressDoesNotMatch() {
        assertThat(only("ul. Batorego 815, 65-735 Zielona Góra")).isNull();
        assertThat(only("ul. Szosa Kisielińska 2, Zielona Góra")).isNull();
        assertThat(DeliveryAddress.normalizeAll(List.of())).isNull();
        assertThat(DeliveryAddress.matches(null, MAKOWA)).isFalse();
    }

    @Test
    void severalThirdPartiesKeepEachAddress() {
        String both = DeliveryAddress.normalizeAll(List.of("UL.SZOSA KISIELINSKA 22", "Makowa 14, 65-382 Zielona Góra"));
        assertThat(DeliveryAddress.matches(both, KISIELINSKA)).isTrue();
        assertThat(DeliveryAddress.matches(both, MAKOWA)).isTrue();
        assertThat(DeliveryAddress.matches(both, BATOREGO)).isFalse();
    }

    private static String only(String address) {
        String normalized = DeliveryAddress.normalizeAll(List.of(address));
        List<String> matching = List.of(KISIELINSKA, MAKOWA, BATOREGO).stream()
                .filter(key -> DeliveryAddress.matches(normalized, key)).toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }
}
