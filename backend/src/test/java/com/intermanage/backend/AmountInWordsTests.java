package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.AmountInWords;

class AmountInWordsTests {

    private static String words(String amount) {
        return AmountInWords.of(new BigDecimal(amount));
    }

    @Test
    void writesZlotyWithGrosze() {
        assertThat(words("0.50")).isEqualTo("zero złotych 50/100");
        assertThat(words("1")).isEqualTo("jeden złoty 00/100");
        assertThat(words("2.05")).isEqualTo("dwa złote 05/100");
        assertThat(words("5")).isEqualTo("pięć złotych 00/100");
        assertThat(words("12")).isEqualTo("dwanaście złotych 00/100");
        assertThat(words("22")).isEqualTo("dwadzieścia dwa złote 00/100");
        assertThat(words("123.45")).isEqualTo("sto dwadzieścia trzy złote 45/100");
        assertThat(words("214")).isEqualTo("dwieście czternaście złotych 00/100");
    }

    @Test
    void writesThousandsAndMillions() {
        assertThat(words("1000")).isEqualTo("tysiąc złotych 00/100");
        assertThat(words("2500.10")).isEqualTo("dwa tysiące pięćset złotych 10/100");
        assertThat(words("12345")).isEqualTo("dwanaście tysięcy trzysta czterdzieści pięć złotych 00/100");
        assertThat(words("1001001")).isEqualTo("milion tysiąc jeden złotych 00/100");
        assertThat(words("3000002")).isEqualTo("trzy miliony dwa złote 00/100");
        assertThat(words("115000")).isEqualTo("sto piętnaście tysięcy złotych 00/100");
    }
}
