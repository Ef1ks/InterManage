package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.OrderFile;

/** Plik zamówienia jak {@code pliki/COMMANDE_20261105_084900_ImpEk_ImpEk.traite0} z programu sklepu. */
class OrderFileTests {

    @Test
    void contentMatchesFileFromStoreProgram() {
        byte[] content = OrderFile.content(List.of(
                new OrderFile.Line("5908243692998", 1),
                new OrderFile.Line("4001163165359", 4),
                new OrderFile.Line("2992090000000", 1)));
        assertThat(new String(content, StandardCharsets.US_ASCII))
                .isEqualTo("590824369299801\r\n400116316535904\r\n299209000000001\r\n");
    }

    @Test
    void shortEanIsPadded() {
        byte[] content = OrderFile.content(List.of(
                new OrderFile.Line("80053828", 12),
                new OrderFile.Line("05901234123457", 99)));
        assertThat(new String(content, StandardCharsets.US_ASCII))
                .isEqualTo("000008005382812\r\n590123412345799\r\n");
    }

    @Test
    void wrongLineRejectsWholeFile() {
        assertThatThrownBy(() -> OrderFile.content(List.of(new OrderFile.Line("CUKINIA", 1))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OrderFile.content(List.of(new OrderFile.Line("5908243692998", 100))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void fileNameHasLoginTwice() {
        assertThat(OrderFile.fileName("pdv_10023")).matches("COMMANDE_\\d{8}_\\d{6}_pdv10023_pdv10023\\.traite0");
    }
}
