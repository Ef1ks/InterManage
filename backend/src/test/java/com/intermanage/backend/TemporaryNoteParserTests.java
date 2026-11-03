package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.basedelivery.component.TemporaryNoteParser;
import com.intermanage.backend.basedelivery.dto.TemporaryNoteResponse;
import com.intermanage.backend.common.exception.InvalidReportException;

/** „Tymczasowy bon dostawy” z Mercalys ({@code pliki/roznice.xlsx}, uszkodzone polskie litery w nagłówkach). */
class TemporaryNoteParserTests {

    private final TemporaryNoteParser parser = new TemporaryNoteParser();

    /**
     * Wiersze jak w {@code pliki/roznice.xlsx} odczytanym przez {@code XlsxReader} (plików .xlsx nie trzymamy
     * w repozytorium): polskie litery w nagłówkach system zapisuje jako znak zastępczy.
     */
    private static final List<List<String>> MERCALYS_SHEET = List.of(
            List.of("SCAPR POLSKA SP.Z.O.O."),
            List.of("JANIKOWSKA 33"),
            List.of("61070 POZNA\uFFFD   POZNA\uFFFD"),
            List.of("POLSKA"),
            List.of("\n\nNIP: 7822137332"),
            List.of("Dostawca 539"),
            List.of("Tel. dost: 61 6651100"),
            List.of("Faks dost. 61 6651227"),
            List.of("Tymczasowy bon dostawy"),
            List.of("Numer dostawy: 10727"),
            List.of("Identyfikacja zam\uFFFDwienia "),
            List.of("Data : 03/11/2026  18:06:39"),
            List.of(),
            List.of("Kod ITM8", "EAN Prio", "Referencja dostawcy", "Nazwa artyku\uFFFDu", "VAT", "Ilo\uFFFD\uFFFD dost.",
                    "Jednostk. cena zakupu", "Jednostkowa C.S.", "Warto\uFFFD\uFFFD zakupu netto", "Mar\uFFFDa %"),
            List.of("0000099011768", "5900084239684", "61408551", "KOLENDRA LISCIE 6G", "5", "20", "1.55", "2.79",
                    "31", "39.68"),
            List.of("0000099011770", "5900084032018", "61408553", "ZIOLKA DO SALATEK 10G", "8", "20", "1.19", "2.99",
                    "23.8", "52.79"),
            List.of("0000099011771", "5900084143011", "61408554", "CEBULA GRANULOWANA 20G", "5", "20", "1.69", "5.99",
                    "33.8", "67.02"),
            List.of("0000099021595", "5901135067423", "539", "PRZYPRAWA DO KOMPOTU", "23", "1", "1.17", "1.99",
                    "1.17", "22.51"),
            List.of(),
            List.of(),
            List.of("Ilo\uFFFD\uFFFD linii: 000004"));

    @Test
    void readsNoteFromMercalys() throws Exception {
        TemporaryNoteResponse note = parser.parse(MERCALYS_SHEET, "roznice.xlsx");

        assertThat(note.supplierCode()).isEqualTo("539");
        assertThat(note.supplierName()).isEqualTo("SCAPR POLSKA SP.Z.O.O.");
        assertThat(note.number()).isEqualTo("10727");
        assertThat(note.createdAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 18, 6, 39));
        assertThat(note.lines()).hasSize(4);
        TemporaryNoteResponse.Line first = note.lines().getFirst();
        assertThat(first.itm8()).isEqualTo("0000099011768");
        assertThat(first.ean()).isEqualTo("5900084239684");
        assertThat(first.reference()).isEqualTo("61408551");
        assertThat(first.name()).isEqualTo("KOLENDRA LISCIE 6G");
        assertThat(first.quantity()).isEqualByComparingTo("20");
        assertThat(first.purchasePrice()).isEqualByComparingTo("1.55");
        assertThat(first.salePrice()).isEqualByComparingTo("2.79");
        assertThat(first.netValue()).isEqualByComparingTo("31");
        assertThat(note.lines().getLast().vatPercent()).isEqualByComparingTo(BigDecimal.valueOf(23));
    }

    @Test
    void rejectsFileWithoutFooterOrWithWrongLineCount() {
        List<List<String>> header = List.of(
                List.of("Dostawca 538"),
                List.of("Kod ITM8", "EAN Prio", "Referencja dostawcy", "Nazwa artykułu", "VAT", "Ilość dost."),
                List.of("0000099011768", "5900084239684", "61408551", "KOLENDRA", "5", "20"));

        assertThatThrownBy(() -> parser.parse(header, "bon.xlsx"))
                .isInstanceOf(InvalidReportException.class).hasMessageContaining("Ilość linii");

        List<List<String>> wrongCount = new ArrayList<>(header);
        wrongCount.add(List.of("Ilość linii: 000002"));
        assertThatThrownBy(() -> parser.parse(wrongCount, "bon.xlsx"))
                .isInstanceOf(InvalidReportException.class).hasMessageContaining("1 linii");
    }

    @Test
    void rejectsOtherFiles() {
        assertThatThrownBy(() -> parser.parse(List.of(List.of("Sklep", "Data_Bl", "Nr_Bl")), "dostawa.xls"))
                .isInstanceOf(InvalidReportException.class).hasMessageContaining("tymczasowy bon");
    }
}
