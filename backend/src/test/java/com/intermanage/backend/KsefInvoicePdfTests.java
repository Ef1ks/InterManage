package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.intermanage.backend.common.component.PdfFonts;
import com.intermanage.backend.ksef.component.KsefInvoicePdf;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/** Odczyt faktury FA(3) z XML i jej PDF z kodem QR weryfikacji KSeF. */
class KsefInvoicePdfTests {

    private static final String KSEF_NUMBER = "1111111111-20261019-0100A0B0C0D0-1F";

    private final KsefInvoiceReader reader = new KsefInvoiceReader();
    private final KsefInvoicePdf pdf = new KsefInvoicePdf(reader, new PdfFonts(), "https://api-test.ksef.mf.gov.pl/v2");

    @Test
    void readsFa3InvoiceIntoReadableFields() throws IOException {
        KsefInvoiceReader.Invoice invoice = reader.read(fixture());

        assertThat(invoice.formCode()).isEqualTo("FA (3)");
        assertThat(invoice.kind()).isEqualTo("Faktura VAT");
        assertThat(invoice.number()).isEqualTo("FV/2/2026");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(invoice.seller().name()).isEqualTo("Okręgowa Spółdzielnia Mleczarska „Łąka”");
        assertThat(invoice.buyer().identifiers()).containsExactly("NIP 5265877635");
        assertThat(invoice.otherParties()).singleElement().satisfies(party -> {
            assertThat(party.role()).isEqualTo("Odbiorca");
            assertThat(party.identifiers()).containsExactly("IDWew 5265877635-00001");
        });
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().getFirst().rate()).isEqualTo("5%");
        assertThat(invoice.lines().getFirst().details()).contains("GTIN: 5900000000012");
        // Faktura od netto: brutto pozycji wyliczone ze stawki.
        assertThat(invoice.lines().getFirst().unitGross()).isEqualByComparingTo("2.10");
        assertThat(invoice.lines().getFirst().gross()).isEqualByComparingTo("210.00");
        assertThat(invoice.lines().get(1).unitGross()).isEqualByComparingTo("9.84");
        assertThat(invoice.lines().get(1).gross()).isEqualByComparingTo("123.00");
        assertThat(invoice.lines().getFirst().grossComputed()).isTrue();
        assertThat(invoice.taxes()).extracting(KsefInvoiceReader.TaxRow::rate).containsExactly("23%", "5%");
        assertThat(invoice.annotations()).containsExactly("Mechanizm podzielonej płatności");
        assertThat(invoice.payment()).contains(
                new KsefInvoiceReader.Entry("Termin płatności", "02.11.2026 (14 dni od wystawienia)"),
                new KsefInvoiceReader.Entry("Forma płatności", "przelew"),
                new KsefInvoiceReader.Entry("Rachunek bankowy",
                        "12 3456 7890 1234 5678 9012 3456\nBank Przykładowy S.A."));
        assertThat(invoice.additional()).contains(new KsefInvoiceReader.Entry("Dokumenty WZ", "WZ/881/2026"));
        assertThat(invoice.registries()).contains(new KsefInvoiceReader.Entry("KRS", "0000123456"));
    }

    @Test
    void rendersPdfWithAllImportantData() throws IOException {
        byte[] file = pdf.pdf(fixture(), KSEF_NUMBER, "1111111111", Instant.parse("2026-10-19T09:20:00Z"));

        assertThat(new String(file, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        PdfReader pdfReader = new PdfReader(file);
        String text = new PdfTextExtractor(pdfReader).getTextFromPage(1);
        assertThat(text).contains("Faktura VAT", "FV/2/2026", KSEF_NUMBER, "Okręgowa Spółdzielnia Mleczarska",
                "TRYTOMA Sp. z o.o.", "Intermarché Zielona Góra", "Mleko 3,2% 1 l karton", "333,00 PLN",
                "Mechanizm podzielonej płatności", "12 3456 7890 1234 5678 9012 3456", "Strona 1 z");
    }

    @Test
    void verificationUrlFollowsKsefQrFormat() throws Exception {
        byte[] xml = fixture();
        String hash = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(xml));
        assertThat(pdf.verificationUrl(xml, "1111111111", LocalDate.of(2026, 10, 19)))
                .isEqualTo("https://qr-test.ksef.mf.gov.pl/invoice/1111111111/19-10-2026/" + hash);
        assertThat(KsefInvoicePdf.verificationBaseUrl("https://api.ksef.mf.gov.pl/v2"))
                .isEqualTo("https://qr.ksef.mf.gov.pl");
    }

    @Test
    void rejectsXmlWithDoctype() {
        byte[] xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Faktura [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Faktura>&x;</Faktura>""".getBytes(StandardCharsets.UTF_8);
        assertThrows(KsefInvoiceReader.InvalidInvoiceException.class, () -> reader.read(xml));
    }

    private static byte[] fixture() throws IOException {
        return new ClassPathResource("ksef/faktura-fa3.xml").getContentAsByteArray();
    }
}
