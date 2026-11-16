package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.basedelivery.component.BaseNoteFile;

/** Plik dostawy dla programu sklepu w układzie pliku bazy LIVRJPOL-095_09516018207_00538-10023-26043004.TXT. */
class BaseNoteFileTests {

    private static BaseNoteFile.Line line(String itm8, String region, String quantity, String purchase,
            String retail, String ean, String vat) {
        return new BaseNoteFile.Line(itm8, region, new BigDecimal(quantity), new BigDecimal(purchase),
                new BigDecimal(retail), ean, new BigDecimal(vat));
    }

    @Test
    void writesLinesLikeBaseFile() {
        BaseNoteFile.File file = BaseNoteFile.create("10023", "09516018207", LocalDate.of(2026, 4, 30), "00538",
                List.of(
                        line("61589397", "01", "14", "3.89", "5.29", "5906747177263", "5.00"),
                        line("03283930", "14", "20", "10.40", "10.99", "2192040000008", "5.00"),
                        line("00809804", "01", "25", "2.2900", "3.59", "0000084460547", "23.00"),
                        line("07076912", "01", "12", "3.49", "4.79", "8682790002543", "5.00"),
                        line("07076912", "01", "6", "3.49", "4.79", "8682790002543", "5.00"),
                        line("03430646", "14", "6", "5.30", "7.99", null, "5.00"),
                        line("01786951", "26", "2.558", "62.30", "69.99", "2999020000009", "5.00")));

        assertThat(file.fileName()).isEqualTo("LIVRJPOL-095_09516018207_00538-10023-26043004.TXT");
        assertThat(file.lineCount()).isEqualTo(6);
        assertThat(new String(file.content(), StandardCharsets.US_ASCII)).isEqualTo("""
                "30/04/2026","09516018207","01","00809804","PPF",000025.000,0000002.29,0000003.59,"84460547     ",23.00,"538"
                "30/04/2026","09516018207","01","07076912","PPF",000018.000,0000003.49,0000004.79,"8682790002543",05.00,"538"
                "30/04/2026","09516018207","14","03283930","PPV",000020.000,0000010.40,0000010.99,"2192040000008",05.00,"538"
                "30/04/2026","09516018207","14","03430646","PPF",000006.000,0000005.30,0000007.99,"0000000000000",05.00,"538"
                "30/04/2026","09516018207","26","01786951","PPV",000002.558,0000062.30,0000069.99,"2999020000009",05.00,"538"
                "30/04/2026","09516018207","01","61589397","PPF",000014.000,0000003.89,0000005.29,"5906747177263",05.00,"538"
                """);
    }
}
