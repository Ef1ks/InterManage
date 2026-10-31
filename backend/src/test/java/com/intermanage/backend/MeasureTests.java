package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.Measure;

/** Rodzaj sprzedaży, waga i miara w nazwie nowego artykułu (Wprowadzanie danych). */
class MeasureTests {

    @Test
    void parsesMeasureFromInvoiceNames() {
        assertMeasure("Chleb Wyborny 150g", "kg", "0.150", "150G");
        assertMeasure("Sok jabłkowy 0,5L", "L", "0.500", "500ML");
        assertMeasure("Woda 1500 ml", "L", "1.500", "1,5L");
        assertMeasure("Astro Salami Szlachetne ca.1,75kg, 2St", "kg", "1.750", "1,75KG");
        assertMeasure("Mąka pszenna 1 kg", "kg", "1.000", "1KG");
        // Karton "8/360 g" i "150g/12" nie zmienia sprzedawanej sztuki.
        assertMeasure("Kielbaski wiejskie wsloiku 8/360 g.", "kg", "0.360", "360G");
        assertMeasure("Ferrero Ciastka Kinder DUO 150g/12", "kg", "0.150", "150G");
        // Wielopak: waga to suma.
        assertMeasure("WAFI | Krem dla kota - kurczak [5x14g]", "kg", "0.070", "5X14G");
        assertMeasure("Napój 6 x 1,5 L", "L", "9.000", "6X1,5L");
        assertThat(Measure.parse("Transport")).isNull();
        // "+50gr." to gratis, nie gramy.
        assertThat(Measure.parse("Chusteczki Bella Happy a_150 (100+50gr.)(25)")).isNull();
        assertThat(Measure.piece().label()).isEmpty();
    }

    @Test
    void normalizesNamesForCustomers() {
        Measure cheese = Measure.parse("350g");
        assertThat(Measure.normalizeName("Ser Liliput 350 g.", cheese)).isEqualTo("SER LILIPUT 350G");
        assertThat(Measure.normalizeName("Mleko UHT smak. truskawkowy 0.2 l", Measure.parse("200ml")))
                .isEqualTo("MLEKO UHT SMAK TRUSKAWKOWY 200ML");
        assertThat(Measure.normalizeName("Kiełbaski wiejskie 8/360 g", Measure.parse("360 g")))
                .isEqualTo("KIELBASKI WIEJSKIE 360G");
        assertThat(Measure.normalizeName("Pałeczki owocowe 60g/20szt/", Measure.parse("60g")))
                .isEqualTo("PALECZKI OWOCOWE 60G");
        // Miara z rodzaju i wagi zastępuje tę z nazwy.
        assertThat(Measure.normalizeName("Woda 1500 ml", Measure.of("L", new BigDecimal("1.5"))))
                .isEqualTo("WODA 1,5L");
        assertThat(Measure.normalizeName("Zapalniczka", Measure.piece())).isEqualTo("ZAPALNICZKA");
        assertThat(Measure.of("A.Wa", BigDecimal.ONE).unit()).isEqualTo("kg");
    }

    @Test
    void goodsInvoicedPerKgAreSoldByWeight() {
        assertThat(Measure.fromInvoice("Ser Pleś.Brie La Polle naturalny 1600g", "kg")).satisfies(measure -> {
            assertThat(measure.unit()).isEqualTo("kg");
            assertThat(measure.netContent()).isEqualByComparingTo("1");
        });
        assertThat(Measure.fromInvoice("FILET Z PIERSI KURCZĄT", "kg").netContent()).isEqualByComparingTo("1");
        // Mała paczka fakturowana w kg zostaje sztuką z wagą z nazwy, a "szt" na fakturze nie zmienia miary.
        assertThat(Measure.fromInvoice("Chleb żytni 400g", "kg").netContent()).isEqualByComparingTo("0.4");
        assertThat(Measure.fromInvoice("Ser Pleś.Brie 1600g", "szt").netContent()).isEqualByComparingTo("1.6");
        assertThat(Measure.fromInvoice("Transport", "szt")).isNull();
    }

    private static void assertMeasure(String name, String unit, String netContent, String label) {
        Measure measure = Measure.parse(name);
        assertThat(measure).as(name).isNotNull();
        assertThat(measure.unit()).as(name).isEqualTo(unit);
        assertThat(measure.netContent()).as(name).isEqualByComparingTo(netContent);
        assertThat(measure.label()).as(name).isEqualTo(label);
    }
}
