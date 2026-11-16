package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.mea.component.AthosHistoryParser;

/**
 * Dziennik ATHOS (Histo.Log). Prawdziwego pliku jeszcze nie mamy, więc linie są ułożone dokładnie według pozycji
 * znaków z eMEA ({@code OdczytPlikuHistoryLog}); po pierwszym prawdziwym pliku dochodzi test na nim.
 */
public class AthosHistoryParserTests {

    /** Linia ze spacjami, z tekstami wpisanymi od podanych znaków. */
    static String line(Object... columnsAndTexts) {
        StringBuilder line = new StringBuilder(" ".repeat(90));
        for (int i = 0; i < columnsAndTexts.length; i += 2) {
            int at = (Integer) columnsAndTexts[i];
            String text = (String) columnsAndTexts[i + 1];
            line.replace(at, at + text.length(), text);
        }
        return line.toString().stripTrailing();
    }

    /** Wysyłka zamówień MEA z datą od znaku 58 (między datą a godziną znak ¦ jak w eMEA). */
    static String header(String date) {
        return line(0, "14/11 ATHOS", 20, "Envio de encomendas MEA", 58, date);
    }

    /** Para linii pozycji: numer MEA od znaku 34, pozycje od 23 (4 znaki) i kartony od 41 (5 znaków). */
    static List<String> entry(String number, String positions, String colis) {
        return List.of(line(10, "MEA", 34, number), line(0, "  ", 10, "Pozycje:", 23, positions, 30, "COLIS:", 41,
                colis));
    }

    public static byte[] file(List<String> lines) {
        return String.join("\r\n", lines).getBytes(AthosHistoryParser.CHARSET);
    }

    @Test
    void readsSendsUnderEachHeader() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(line(0, "13/11 ATHOS", 20, "Inna operacja", 58, "13/11/2026 07:00:00"));
        lines.add(header("14/11/2026¦08:15:30"));
        lines.addAll(entry("81534", "  43", "   27"));
        lines.addAll(entry("81419", " 120", "  310"));
        lines.add(line(0, "14/11 ATHOS", 20, "Koniec"));
        lines.add(header("15/11/2026¦09:00:05"));
        lines.addAll(entry("81535", "   5", "    6"));

        List<AthosHistoryParser.Send> sends = new AthosHistoryParser().parse(file(lines));

        assertThat(sends).containsExactly(
                new AthosHistoryParser.Send(LocalDateTime.of(2026, 11, 14, 8, 15, 30), "81534", 43, 27),
                new AthosHistoryParser.Send(LocalDateTime.of(2026, 11, 14, 8, 15, 30), "81419", 120, 310),
                new AthosHistoryParser.Send(LocalDateTime.of(2026, 11, 15, 9, 0, 5), "81535", 5, 6));
    }

    @Test
    void skipsBrokenEntriesAndFileWithoutSends() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(header("14/11/2026¦08:15:30"));
        lines.addAll(entry("ABCDE", "  43", "   27"));
        assertThat(new AthosHistoryParser().parse(file(lines))).isEmpty();
        assertThat(new AthosHistoryParser().parse(file(List.of("cokolwiek", "")))).isEmpty();
    }
}
