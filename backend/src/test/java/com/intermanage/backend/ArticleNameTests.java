package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.ArticleName;
import com.intermanage.backend.dataentry.component.Measure;

class ArticleNameTests {

    private static final Measure HALF_LITRE = new Measure(Measure.L, new BigDecimal("0.5"), 1);

    @Test
    void recognizesDepositInInvoiceNames() {
        assertThat(ArticleName.mentionsDeposit("Tymbark Nap. Aseptic Butelka 2L - Maspex. KAUCJA")).isTrue();
        assertThat(ArticleName.mentionsDeposit("PIĄTNICA ROŚLINNA MIGDAŁ.+KAUCJA 1l(6)")).isTrue();
        assertThat(ArticleName.mentionsDeposit("3 CYTRYNY 330ML PUSZKA KAUC")).isTrue();
        assertThat(ArticleName.mentionsDeposit("Napój z kaucją 1l")).isTrue();
        assertThat(ArticleName.mentionsDeposit("Sok system kaucyjny 1l")).isTrue();

        assertThat(ArticleName.mentionsDeposit("Sok jabłkowy bez kaucji 1l")).isFalse();
        assertThat(ArticleName.mentionsDeposit("Rękawice kauczukowe")).isFalse();
        assertThat(ArticleName.mentionsDeposit("Ser Liliput 350 g")).isFalse();
        assertThat(ArticleName.mentionsDeposit(null)).isFalse();
    }

    @Test
    void removesDepositFromName() {
        assertThat(ArticleName.clean("Napój jabłko +KAUCJA", null, HALF_LITRE)).isEqualTo("NAPOJ JABLKO 500ML");
        assertThat(ArticleName.clean("NAPOJ JABLKO Z KAUCJA 500ML", null, HALF_LITRE)).isEqualTo("NAPOJ JABLKO 500ML");
        assertThat(ArticleName.clean("NAPOJ KAUC JABLKO", null, HALF_LITRE)).isEqualTo("NAPOJ JABLKO 500ML");
    }

    @Test
    void removesBrandFromName() {
        assertThat(ArticleName.clean("ALE BROWAR TMAVE 500ML", "Ale Browar", HALF_LITRE)).isEqualTo("TMAVE 500ML");
        assertThat(ArticleName.clean("PREZERWATYWY SKYN LARGE", "SKYN", Measure.piece()))
                .isEqualTo("PREZERWATYWY LARGE");
        assertThat(ArticleName.clean("NAPOJ COCA COLA ZERO", "Coca-Cola", HALF_LITRE)).isEqualTo("NAPOJ ZERO 500ML");
        assertThat(ArticleName.clean("SER ŁACIATY", "Łaciaty", Measure.piece())).isEqualTo("SER");
    }

    @Test
    void brandInsideLongerWordStays() {
        assertThat(ArticleName.clean("KREM BONAPARTE", "BONA", Measure.piece())).isEqualTo("KREM BONAPARTE");
    }

    @Test
    void keepsWordsWhenOnlyMeasureWouldRemain() {
        assertThat(ArticleName.clean("MILKA 500ML", "MILKA", HALF_LITRE)).isEqualTo("MILKA 500ML");
        assertThat(ArticleName.clean("KAUCJA", null, Measure.piece())).isEqualTo("KAUCJA");
    }

    @Test
    void withoutBrandNameIsJustNormalized() {
        assertThat(ArticleName.clean("Mleko 3.2% 500 ml", null, HALF_LITRE)).isEqualTo("MLEKO 3,2% 500ML");
        assertThat(ArticleName.clean("Mleko 500 ml", " - ", HALF_LITRE)).isEqualTo("MLEKO 500ML");
    }
}
