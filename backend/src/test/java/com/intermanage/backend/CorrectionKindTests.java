package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.ksef.component.CorrectionKind;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;

/** Korekta zmieniająca ilości wobec korekty tylko na kwoty, w obu formach zapisu korekt. */
class CorrectionKindTests {

    private final KsefInvoiceReader reader = new KsefInvoiceReader();

    @Test
    void beforeRowsWithOtherQuantityChangeQuantity() {
        assertThat(changes(row("1", true, "10", "2.50") + row("1", false, "8", "2.50"))).isTrue();
    }

    @Test
    void beforeRowsWithOnlyOtherPriceChangeAmount() {
        assertThat(changes(row("1", true, "10", "2.50") + row("1", false, "10", "2.20")
                + row("2", false, "5", "3.00", "INNY TOWAR"))).isFalse();
    }

    @Test
    void megapromotionSplittingRowIntoTwoPricesChangesAmountOnly() {
        // Baza SCA PR, korekta 0695421740: część ilości w nowej cenie, suma artykułu bez zmian.
        assertThat(changes(row("49", true, "180", "4.95", "SLIWKA POLSKA 6KG CASI", "03306865")
                + row("49", false, "88.61", "4.95", "SLIWKA POLSKA 6KG CASI", "03306865")
                + row("49", false, "91.39", "3.75", "SLIWKA POLSKA 6KG CASI", "03306865")
                + row("47", true, "35", "7.90", "WINO.BIALE KL1-7KG", "03307743")
                + row("47", false, "10.72", "7.90", "WINO.BIALE KL1-7KG", "03307743")
                + row("47", false, "24.28", "6.55", "WINO.BIALE KL1-7KG", "03307743"))).isFalse();
        // Ta sama forma z mniejszą sumą to zwrot.
        assertThat(changes(row("49", true, "180", "4.95", "SLIWKA POLSKA 6KG CASI", "03306865")
                + row("49", false, "88.61", "4.95", "SLIWKA POLSKA 6KG CASI", "03306865")
                + row("49", false, "81.39", "3.75", "SLIWKA POLSKA 6KG CASI", "03306865"))).isTrue();
    }

    @Test
    void replacedArticleUnderSameNumberChangesQuantity() {
        assertThat(changes(row("1", true, "10", "2.50", "SALATA MASLOWA")
                + row("1", false, "10", "2.50", "SALATA LODOWA"))).isTrue();
    }

    @Test
    void differenceRowsReturnChangesQuantity() {
        assertThat(changes(row("1", false, "-3", "2.50"))).isTrue();
    }

    @Test
    void differenceRowsWithNegativePriceOrZeroQuantityChangeAmount() {
        // Obniżka ceny: ilość towaru z ceną na minusie (np. Gniezno) albo sama kwota przy ilości 0.
        assertThat(changes(row("1", false, "10", "-0.30") + row("2", false, "0", "-12.00"))).isFalse();
    }

    @Test
    void depositsDoNotCount() {
        assertThat(changes(row("1", false, "-24", "0.50", "KAUCJA BUTELKA"))).isFalse();
        assertThat(changes("")).isFalse();
    }

    private boolean changes(String rows) {
        byte[] xml = """
                <Faktura xmlns="http://crd.gov.pl/wzor/2025/06/25/13775/">
                  <Naglowek><KodFormularza kodSystemowy="FA (3)">FA</KodFormularza></Naglowek>
                  <Fa><RodzajFaktury>KOR</RodzajFaktury>%s</Fa>
                </Faktura>""".formatted(rows).getBytes(StandardCharsets.UTF_8);
        return CorrectionKind.changesQuantity(reader.read(xml).lines(), reader.lineCodes(xml));
    }

    private static String row(String number, boolean before, String quantity, String price) {
        return row(number, before, quantity, price, "TOWAR");
    }

    private static String row(String number, boolean before, String quantity, String price, String name) {
        return row(number, before, quantity, price, name, null);
    }

    private static String row(String number, boolean before, String quantity, String price, String name,
            String index) {
        return """
                <FaWiersz><NrWierszaFa>%s</NrWierszaFa><P_7>%s</P_7>%s<P_8A>szt</P_8A><P_8B>%s</P_8B>
                  <P_9A>%s</P_9A><P_12>23</P_12>%s</FaWiersz>"""
                .formatted(number, name, index != null ? "<Indeks>" + index + "</Indeks>" : "", quantity, price,
                        before ? "<StanPrzed>1</StanPrzed>" : "");
    }
}
