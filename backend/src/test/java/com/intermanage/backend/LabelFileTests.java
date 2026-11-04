package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.LabelFile;

/** Plik etykiet jak {@code pliki/ETIQUETTE_20261103_105800_ImpEk_ImpEk.traite0} z programu sklepu. */
class LabelFileTests {

    @Test
    void contentMatchesFileFromStoreProgram() {
        byte[] content = LabelFile.content(List.of(
                new LabelFile.Line("2405480000000", 1),
                new LabelFile.Line("2405520000000", 1)));
        assertThat(new String(content, StandardCharsets.US_ASCII))
                .isEqualTo("24054800000000001\r\n24055200000000001\r\n");
    }

    @Test
    void shortEanIsPaddedAndCountHasFourDigits() {
        byte[] content = LabelFile.content(List.of(
                new LabelFile.Line("80053828", 12),
                new LabelFile.Line("05901234123457", 3),
                new LabelFile.Line("CUKINIA", 1)));
        assertThat(new String(content, StandardCharsets.US_ASCII))
                .isEqualTo("00000800538280012\r\n59012341234570003\r\n");
    }

    @Test
    void fileNameHasLoginTwice() {
        assertThat(LabelFile.fileName("pdv_10023", null))
                .matches("ETIQUETTE_\\d{8}_\\d{6}_pdv10023_pdv10023\\.traite0");
    }

    @Test
    void formatWithoutUnderscoresTakesPlaceOfFirstLogin() {
        assertThat(LabelFile.fileName("pdv_10023", "PRO_GAZ_FOR_30"))
                .matches("ETIQUETTE_\\d{8}_\\d{6}_PROGAZFOR30_pdv10023\\.traite0");
    }
}
