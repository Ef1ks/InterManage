package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.dataentry.component.PriceControlFile;

/** Program sklepu dzieli nazwę pliku po „_”, więc części nazwy mają tylko litery i cyfry. */
class ProtoFileNameTests {

    @Test
    void priceControlFileNameHasOnlyLettersAndDigitsInParts() {
        assertThat(PriceControlFile.fileName("DR LEKS", "pdv_10023"))
                .matches("CTRLPRIX_\\d{8}_\\d{6}_DRLEKS_pdv10023\\.traite0");
        assertThat(PriceControlFile.fileName("S&R", "jan.kowalski"))
                .matches("CTRLPRIX_\\d{8}_\\d{6}_SR_jankowalski\\.traite0");
    }

    @Test
    void protoFileNameHasOnlyLettersAndDigitsInLogin() {
        assertThat(ProtoFile.fileName(6, "10023", "pdv_10024"))
                .matches("PROTO6_\\d{8}_\\d{6}_10023_pdv10024\\.Traite0");
        assertThat(ProtoFile.fileName(1, "10023", "___")).matches("PROTO1_\\d{8}_\\d{6}_10023_user\\.Traite0");
    }
}
