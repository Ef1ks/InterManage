package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.XlsWriter;
import com.intermanage.backend.dataentry.component.ProductFile;
import com.intermanage.backend.dataentry.dto.ProductFileRequest;

/** Plik nowych i zmienionych artykułów (CADBEZP .xls) z wprowadzania danych. */
class ProductFileTests {

    @Test
    void rowsFollowTheTemplate() {
        List<List<Object>> rows = ProductFile.rows(List.of(
                new ProductFileRequest.Row("5903983402258", "WODKA DEBOWA 0,7L",
                        "Dębowa Polska", "L", new BigDecimal("0.700"), new BigDecimal("12.3456"),
                        new BigDecimal("23.00"), new BigDecimal("29.99"), "730501", "61658344", "2208 90 56"),
                new ProductFileRequest.Row("CHLEB WIEJSKI", "CHLEB WIEJSKI 500G", null, "kg",
                        new BigDecimal("0.500"), new BigDecimal("3.2"), new BigDecimal("5"), null, null, null, null)));

        assertThat(ProductFile.HEADERS).hasSize(25).doesNotContain("Segment");
        assertThat(ProductFile.HEADERS.subList(19, 24)).containsOnly("");
        assertThat(rows.get(0)).containsExactly("5903983402258", "WODKA DEBOWA 0,7L", "WODKA DEBOWA 0,7L",
                "5903983402258", "1", "1", "L", new BigDecimal("0.700"), "DEBOWA P", new BigDecimal("12.35"),
                BigDecimal.ZERO, new BigDecimal("12.35"), "23%", new BigDecimal("29.99"), new BigDecimal("29.99"),
                BigDecimal.ZERO, "73", "05", "01", null, null, null, null, null, "22089056");
        // Kod zastępczy to brak EAN-u (też w Ref. dostawcy), kg = K, bez nomenklatury puste poziomy.
        List<Object> created = rows.get(1);
        assertThat(created.get(0)).isNull();
        assertThat(created.get(3)).isNull();
        assertThat(created.get(6)).isEqualTo("K");
        assertThat(created.get(12)).isEqualTo("5%");
        assertThat(created.subList(16, 19)).containsOnlyNulls();
        assertThat(created.get(24)).isEqualTo(ProductFile.CN);

        assertThat(ProductFile.fileName("Impek Sp. z o.o.", "FV 12/2026")).isEqualTo("CADBEZP-IMPEK_SP._Z_O.O.-FV_12_2026.xls");
        assertThat(ProductFile.fileName(null, null)).isEqualTo("CADBEZP.xls");
    }

    @Test
    void writesBiff8Workbook() {
        byte[] file = new XlsWriter().write("Arkusz1", ProductFile.HEADERS,
                List.of(Arrays.asList("61527580", "Zażółć", new BigDecimal("1.5"), null)));

        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(header.getLong(0)).isEqualTo(0xE11AB1A1E011CFD0L);
        assertThat(file.length % 512).isZero();
        // Katalog: strumień „Workbook” od sektora 0, z rekordem BOF arkusza BIFF8 na początku.
        int directory = header.getInt(48);
        String entry = new String(file, 512 * (1 + directory) + 128, 16, StandardCharsets.UTF_16LE);
        assertThat(entry).isEqualTo("Workbook");
        assertThat(header.getShort(512)).isEqualTo((short) 0x0809);
        assertThat(header.getShort(516)).isEqualTo((short) 0x0600);
        // Napisy UTF-16 mogą zaczynać się od nieparzystego bajtu.
        String content = new String(file, StandardCharsets.UTF_16LE)
                + new String(file, 1, file.length - 1, StandardCharsets.UTF_16LE);
        assertThat(content).contains("Masa/Objętość").contains("Zażółć");
    }
}
