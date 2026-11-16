package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.mea.component.MeaLeafletParser;

/** Opis gazetki z centrali; wiersze przepisane z „Gazetka nr 49_2026 BOŻE NARODZENIE 2.xlsx”. */
class MeaLeafletParserTests {

    private static final List<String> HEADER = List.of("LP", "ROK", "TYDZIEŃ", "DATA POCZĄTKU", "DATA KOŃCA",
            "NR MEA", "ITM 8", "ITM 8 PROMO", "PRODUKT", "MARKA", "FORMA PROMOCJI", "RODZAJ MEGAPROMOCJI",
            "WARIANT MEGAPROMOCJI", "ILOŚĆ SZTUK W MEGAPROMOCJI", "CENA W MEGAPROMOCJI WIELOSZTUKI", "INNY GRATIS",
            "CENA CESJI DO ROZLICZEŃ", "CENA DLA KLIENTA", "Limit dla klienta", "FORMA DOSTAWY");

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void readsPositionsWithSectionsAndPromoForms() throws Exception {
        List<List<String>> sheet = List.of(
                HEADER,
                row("1", null, null, null, null, null, null, null, "PRODUKTY KLUCZOWE"),
                row("2", "2026", "49", "06.12.2026", "06.12.2026", "81495", "681172", "61662380",
                        "G.SC.X.RM.M.NUTELLA 350G", "NUTELLA",
                        "LIMIT DZIENNY \nRabat tylko dla klientów z KARTĄ MOJE IM / aplikacją\nKLUCZOWY PRODUKT",
                        "X szt. za Y zł", null, "2", "17,98", "", "7,10", "8,99", "2", "BAZA"),
                row("3", null, null, null, null, null, null, null, "PRODUKTY SUCHE"),
                row("4", "2026", "49", "03.12.2026", "09.12.2026", "DDD", null, "DDD", "G.KRAKUSKI 144G", "KRAKUS",
                        "trzeci tańszy lub ten sam gratis", "X + Y (szt.)", "2 + 1", null, "", "", "", "", null,
                        "DDD"));

        MeaLeafletParser.Parsed parsed = new MeaLeafletParser().parse(sheet);

        assertThat(parsed.year()).isEqualTo(2026);
        assertThat(parsed.week()).isEqualTo(49);
        assertThat(parsed.lines()).hasSize(2);
        MeaLeafletParser.Line nutella = parsed.lines().get(0);
        assertThat(nutella.section()).isEqualTo("PRODUKTY KLUCZOWE");
        assertThat(nutella.dateFrom()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(nutella.meaNumber()).isEqualTo("81495");
        assertThat(nutella.itm8()).isEqualTo("681172");
        assertThat(nutella.promoItm8()).isEqualTo("61662380");
        assertThat(nutella.promoForm()).containsExactly("LIMIT DZIENNY",
                "Rabat tylko dla klientów z KARTĄ MOJE IM / aplikacją", "KLUCZOWY PRODUKT");
        assertThat(nutella.megaKind()).isEqualTo("X szt. za Y zł");
        assertThat(nutella.megaQuantity()).isEqualTo(2);
        assertThat(nutella.megaPrice()).isEqualByComparingTo(new BigDecimal("17.98"));
        assertThat(nutella.purchasePrice()).isEqualByComparingTo(new BigDecimal("7.10"));
        assertThat(nutella.clientPrice()).isEqualByComparingTo(new BigDecimal("8.99"));
        assertThat(nutella.clientLimit()).isEqualTo(2);
        assertThat(nutella.deliveryForm()).isEqualTo("BAZA");

        MeaLeafletParser.Line ddd = parsed.lines().get(1);
        assertThat(ddd.section()).isEqualTo("PRODUKTY SUCHE");
        assertThat(ddd.promoItm8()).isNull();
        assertThat(ddd.megaVariant()).isEqualTo("2 + 1");
        assertThat(ddd.clientPrice()).isNull();
    }

    @Test
    void rejectsOtherFile() {
        assertThatThrownBy(() -> new MeaLeafletParser().parse(List.of(List.of("EAN", "NAZWA"))))
                .isInstanceOf(InvalidReportException.class);
    }
}
