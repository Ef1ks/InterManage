package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.product.component.DepositKindReportParser;
import com.intermanage.backend.product.dto.ParsedDepositKindReport;

/** Lista opakowań zwrotnych z systemu sklepu (prawdziwy wydruk z 10023, 26.11.2026). */
class DepositKindReportParserTests {

    @Test
    void readsRealList() throws Exception {
        Path file = Files.createTempFile("deposit-kinds-", ".pdf");
        try (InputStream in = getClass().getResourceAsStream("/pdf/deposit-kinds.pdf")) {
            // PDF-y są w .gitignore: bez pliku na dysku (CI) test się pomija.
            assumeTrue(in != null, "Brak test/resources/pdf/deposit-kinds.pdf");
            Files.write(file, in.readAllBytes());
            ParsedDepositKindReport report = new DepositKindReportParser().parse(file);

            assertThat(report.storeNumber()).isEqualTo("10023");
            assertThat(report.generatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 11, 12, 41));
            assertThat(report.rows()).hasSize(10);
            assertThat(report.rows()).extracting(ParsedDepositKindReport.Row::code)
                    .containsExactly((short) 1, (short) 2, (short) 3, (short) 60, (short) 70, (short) 80, (short) 90,
                            (short) 91, (short) 92, (short) 99);

            ParsedDepositKindReport.Row bottle = report.rows().get(0);
            // Kwota w nazwie nie myli wartości: nazwa bierze wszystko przed kodem VAT.
            assertThat(bottle.name()).isEqualTo("BUTELKA 0,5 L 1,00 ZŁ");
            assertThat(bottle.vatCode()).isEqualTo((short) 7);
            assertThat(bottle.value()).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(bottle.kind()).isEqualTo("Kaucja");

            ParsedDepositKindReport.Row can = report.rows().get(4);
            assertThat(can.name()).isEqualTo("KAUCJA PUSZKA METALOWA");
            assertThat(can.value()).isEqualByComparingTo(new BigDecimal("0.50"));

            assertThat(report.rows().get(2).value()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(report.rows().get(6).vatCode()).isEqualTo((short) 1);
            assertThat(report.rows().get(9).value()).isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
