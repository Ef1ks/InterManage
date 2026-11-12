package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.InvoiceXml;

/** Plik XML faktury dla programu sklepu (windows-1250). */
class InvoiceXmlTests {

    @Test
    void cyrillicLookalikesBecomeLatin() {
        // FAN/ZА664/10/2026 z KSeF: „А” to cyrylica (U+0410), w windows-1250 zostałby „?”.
        byte[] file = InvoiceXml.write(
                new InvoiceXml.Header("FAN/ZА664/10/2026", LocalDate.of(2026, 10, 31), "123", "10023",
                        "1234567890"),
                List.of(new InvoiceXml.Line("SER ŻоŁTY", "5900000000001", new BigDecimal("1.50"),
                        new BigDecimal("5"), BigDecimal.ONE)));

        InvoiceXml.Document document = InvoiceXml.read(file);
        assertThat(document.header().documentNumber()).isEqualTo("FAN/ZA664/10/2026");
        assertThat(document.lines().getFirst().name()).isEqualTo("SER ŻoŁTY");
    }
}
