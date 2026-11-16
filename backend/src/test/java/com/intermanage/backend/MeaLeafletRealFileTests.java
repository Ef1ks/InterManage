package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.mea.component.MeaLeafletParser;

/** Prawdziwe opisy gazetek z centrali, gdy leżą na dysku ({@code emea/} jest poza repozytorium). */
class MeaLeafletRealFileTests {

    private static final Path FOLDER = Path.of("..", "emea", "emea", "api_cad_opis");

    @Test
    void readsAllRealFiles() throws Exception {
        assumeTrue(Files.isDirectory(FOLDER), "brak plików opisu gazetek");
        List<Path> files;
        try (Stream<Path> list = Files.list(FOLDER)) {
            files = list.filter(file -> file.toString().endsWith(".xlsx")).sorted().toList();
        }
        for (Path file : files) {
            List<List<String>> rows = new ArrayList<>();
            new XlsxReader().readFirstSheet(file, (rowNumber, cells) -> {
                while (rows.size() < rowNumber - 1) {
                    rows.add(List.of());
                }
                rows.add(cells);
            });
            MeaLeafletParser.Parsed parsed = new MeaLeafletParser().parse(rows);
            assertThat(parsed.year()).as(file.toString()).isEqualTo(2026);
            assertThat(parsed.lines()).as(file.toString()).hasSizeGreaterThan(900);
            long withPromo = parsed.lines().stream().filter(line -> line.promoItm8() != null).count();
            assertThat(withPromo).as(file.toString()).isGreaterThan(parsed.lines().size() * 95L / 100);
            System.out.printf("%s: %d/%d, %d pozycji, %d z ceną dla klienta, %d z limitem%n", file.getFileName(),
                    parsed.week(), parsed.year(), parsed.lines().size(),
                    parsed.lines().stream().filter(line -> line.clientPrice() != null).count(),
                    parsed.lines().stream().filter(line -> line.clientLimit() != null).count());
        }
    }
}
