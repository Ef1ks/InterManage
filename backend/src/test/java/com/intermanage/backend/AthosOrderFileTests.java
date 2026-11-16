package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.mea.component.AthosOrderFile;

/** Plik zamówienia MEA do ATHOS w formacie eMEA (GenerujZip): rekordy po 45 znaków i ZIP R…. */
class AthosOrderFileTests {

    @Test
    void buildsRecordsLikeEmea() {
        String content = AthosOrderFile.content("81534", List.of(
                new AthosOrderFile.Line("61665965", new int[] {1, 0, 0, 0}),
                new AthosOrderFile.Line("0061665966", new int[] {0, 12, 0, 0}),
                new AthosOrderFile.Line("61665967", new int[4])));

        assertThat(content).isEqualTo(
                "81534" + "61665965" + "0001" + "0000" + "0000" + "0000" + "0000000000000000"
                        + "81534" + "61665966" + "0000" + "0012" + "0000" + "0000" + "0000000000000000"
                        + "81534" + "61665967" + "0000" + "0000" + "0000" + "0000" + "0000000000000000");
        assertThat(content).hasSize(3 * AthosOrderFile.RECORD_LENGTH).doesNotContain("\n");
    }

    @Test
    void namesFilesFromMeaAndStore() {
        String store = AthosOrderFile.storeNumber("10023");
        assertThat(AthosOrderFile.textName("81534", store)).isEqualTo("V8153410.023");
        assertThat(AthosOrderFile.zipName("81534", store)).isEqualTo("R8153410.023");
        assertThatThrownBy(() -> AthosOrderFile.storeNumber("100231")).isInstanceOf(AthosOrderFile.InvalidOrder.class);
        assertThatThrownBy(() -> AthosOrderFile.storeNumber("20023")).isInstanceOf(AthosOrderFile.InvalidOrder.class);
    }

    @Test
    void rejectsWrongCodesAndQuantities() {
        assertThatThrownBy(() -> AthosOrderFile.content("81534",
                List.of(new AthosOrderFile.Line("7127306", new int[4])))).isInstanceOf(AthosOrderFile.InvalidOrder.class);
        assertThatThrownBy(() -> AthosOrderFile.content("81534",
                List.of(new AthosOrderFile.Line("61665965", new int[] {10000, 0, 0, 0}))))
                .isInstanceOf(AthosOrderFile.InvalidOrder.class);
        assertThatThrownBy(() -> AthosOrderFile.content("8153",
                List.of(new AthosOrderFile.Line("61665965", new int[4])))).isInstanceOf(AthosOrderFile.InvalidOrder.class);
    }

    @Test
    void zipsTextFileWithoutFolder() throws Exception {
        byte[] zip = AthosOrderFile.zip("V8153410.023", "abc");
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            assertThat(entry.getName()).isEqualTo("V8153410.023");
            assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("abc");
            assertThat(in.getNextEntry()).isNull();
        }
    }
}
