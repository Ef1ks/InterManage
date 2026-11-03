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
                        new BigDecimal("23.00"), new BigDecimal("29.99"), "730501", "61658344", "2208 90 56",
                        new BigDecimal("6.000")),
                new ProductFileRequest.Row("CHLEB WIEJSKI", "CHLEB WIEJSKI 500G", null, "kg",
                        new BigDecimal("0.500"), new BigDecimal("3.2"), new BigDecimal("5"), null, null, null, null, null)));

        // Układ 1:1 jak plik CADBEZP z Impeka: Segment w kolumnie 20, potem cztery puste.
        assertThat(ProductFile.HEADERS).hasSize(25).element(19).isEqualTo("Segment");
        assertThat(ProductFile.HEADERS.subList(20, 24)).containsOnly("");
        assertThat(rows.get(0)).containsExactly("5903983402258", "WODKA DEBOWA 0,7L", "WODKA DEBOWA 0,7L",
                "5903983402258", new BigDecimal("6"), new BigDecimal("6"), "L", new BigDecimal("0.700"), "DEBOWA P", new BigDecimal("12.35"),
                BigDecimal.ZERO, new BigDecimal("12.35"), "23%", new BigDecimal("29.99"), new BigDecimal("29.99"),
                BigDecimal.ZERO, "73", "05", "01", BigDecimal.ZERO, null, null, null, null, "22089056");
        // Kod zastępczy to brak EAN-u (też w Ref. dostawcy), kg = K, bez nomenklatury puste poziomy.
        List<Object> created = rows.get(1);
        assertThat(created.get(0)).isNull();
        assertThat(created.get(3)).isNull();
        assertThat(created.subList(4, 6)).containsOnly(BigDecimal.ONE);
        assertThat(created.get(6)).isEqualTo("K");
        assertThat(created.get(12)).isEqualTo("5%");
        assertThat(created.subList(16, 19)).containsOnlyNulls();
        assertThat(created.get(19)).isSameAs(com.intermanage.backend.common.component.XlsWriter.EMPTY_TEXT);
        assertThat(created.get(24)).isEqualTo(ProductFile.CN);

        assertThat(ProductFile.fileName("Impek Sp. z o.o.", "FV 12/2026")).isEqualTo("CADBEZP-IMPEK_SP._Z_O.O.-FV_12_2026.xls");
        assertThat(ProductFile.fileName(null, null)).isEqualTo("CADBEZP.xls");
    }

    /**
     * Program sklepu wczytuje tylko pliki CADBEZP takie jak z Impeka (inne kończyły się „Błąd podczas czytania pliku
     * CadArtFrs.xls”), więc te same cztery artykuły muszą dać plik identyczny bajt w bajt z ich plikiem.
     */
    @Test
    void sameBytesAsImpekFile() throws Exception {
        List<ProductFileRequest.Row> articles = List.of(
                impekRow("5901289811187", "FRYTKI KARBOWANE 750G", "0.750", "6.66", "10.99", "192002"),
                impekRow("5901289811194", "FRYTKI PROSTE 750G", "0.750", "6.66", "10.99", "192001"),
                impekRow("8710449912971", "FRYTKI KARBOWANE 2,5KG", "2.500", "16.90", "25.99", "192002"),
                impekRow("8710449912988", "FRYTKI PROSTE 2,5KG", "2.500", "16.90", "25.99", "192001"));
        byte[] file = new XlsWriter().write("Arkusz1", ProductFile.HEADERS, ProductFile.rows(articles));

        byte[] impek;
        try (var in = getClass().getResourceAsStream("/xls/cadbezp-impek.xls")) {
            impek = in.readAllBytes();
        }
        assertThat(file).isEqualTo(impek);
    }

    private static ProductFileRequest.Row impekRow(String ean, String name, String weight, String purchase,
            String sale, String subfamily) {
        return new ProductFileRequest.Row(ean, name, "AVIKO", "kg", new BigDecimal(weight), new BigDecimal(purchase),
                new BigDecimal("5"), new BigDecimal(sale), subfamily, null, null, null);
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

    @Test
    void writesCellsLikeExcel() throws Exception {
        // Ponad 32 wiersze (kilka bloków ROW/DBCELL), powtórzone teksty (SST), liczby RK i NUMBER.
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(Arrays.asList("0061527580", "Zażółć", "P", new BigDecimal("12.35"), i, null,
                    new BigDecimal("0.123456"), -7));
        }
        byte[] file = new XlsWriter().write("Arkusz1", List.of("A", "B", "C", "D", "E", "", "G", "H"), rows);

        List<List<String>> read = new com.intermanage.backend.common.component.XlsReader().readFirstSheet(file);
        assertThat(read).hasSize(41);
        assertThat(read.get(0)).startsWith("A", "B", "C", "D", "E");
        assertThat(read.get(40)).containsExactly("0061527580", "Zażółć", "P", "12.35", "39", null, "0.123456", "-7");
        // RK jak w plikach CADBEZP z Impeka: zawsze double (grosze jako setki ÷100), nigdy całkowite RK.
        assertThat(rk(0)).isEqualTo(0);
        assertThat(rk(1)).isEqualTo(0x3FF00000);
        assertThat(rk(0.15)).isEqualTo(0x402E0001);
        assertThat(rk(5.9)).isEqualTo(0x40827001);
        assertThat(rk(9.95)).isNull(); // 9,95 × 100 = 994,999…, Excel zapisuje NUMBER
        assertThat(rk(0.5)).isEqualTo(0x3FE00000);
        assertThat(rk(1 / 1024.0)).isEqualTo((int) (Double.doubleToLongBits(1 / 1024.0) >>> 32));
        assertThat(rk(0.123456)).isNull();
    }

    private static Integer rk(double value) throws Exception {
        var method = XlsWriter.class.getDeclaredMethod("rk", double.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, value);
    }
}
