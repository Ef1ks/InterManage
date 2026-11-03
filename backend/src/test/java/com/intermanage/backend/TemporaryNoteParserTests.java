package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.basedelivery.component.TemporaryNoteParser;
import com.intermanage.backend.basedelivery.dto.TemporaryNoteResponse;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;

/** „Tymczasowy bon dostawy” z Mercalys ({@code pliki/roznice.xlsx}, uszkodzone polskie litery w nagłówkach). */
class TemporaryNoteParserTests {

    private final TemporaryNoteParser parser = new TemporaryNoteParser();

    @Test
    void readsNoteFromMercalys() throws Exception {
        List<List<String>> sheet = new ArrayList<>();
        new XlsxReader().readFirstSheet(Path.of("src/test/resources/xls/tymczasowy-bon.xlsx"), (rowNumber, cells) -> {
            while (sheet.size() < rowNumber - 1) {
                sheet.add(List.of());
            }
            sheet.add(cells);
        });

        TemporaryNoteResponse note = parser.parse(sheet, "roznice.xlsx");

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
