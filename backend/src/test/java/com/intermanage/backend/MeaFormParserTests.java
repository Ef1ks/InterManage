package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.XlsReader;
import com.intermanage.backend.mea.component.MeaFormParser;

/** Formularz MEA z portalu Grupy Muszkieterów (prawdziwy plik MEA 81534 z 10023, jak w eMEA). */
class MeaFormParserTests {

    @Test
    void readsRealForm() throws Exception {
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/xls/mea-81534.xls")) {
            file = in.readAllBytes();
        }
        MeaFormParser.Form form = new MeaFormParser().parse(new XlsReader().readFirstSheet(file));

        assertThat(form.number()).isEqualTo("81534");
        assertThat(form.title()).isEqualTo("MEA FRANCJA T51 KANWA-FRAIS-");
        assertThat(form.promoFrom()).isEqualTo(LocalDate.of(2026, 12, 17));
        assertThat(form.promoTo()).isEqualTo(LocalDate.of(2026, 12, 23));
        assertThat(form.reservationTo()).isEqualTo(LocalDate.of(2026, 11, 17));
        // eMEA: „R1: 2026-12-14 poniedziałek”, pozostałe rozrzuty puste.
        assertThat(form.dispatches()).isEqualTo(List.of(LocalDate.of(2026, 12, 14)));
        assertThat(form.lines()).hasSize(43);

        MeaFormParser.Line first = form.lines().get(0);
        assertThat(first.lp()).isEqualTo(1);
        assertThat(first.meaItm8()).isEqualTo("61665965");
        assertThat(first.ean()).isEqualTo("3410280146584");
        assertThat(first.name()).isEqualTo("SER CAMEMBERT 23.5%TL.240G");
        assertThat(first.brand()).isEqualTo("TOP BUDGET");
        assertThat(first.unitsPerPack()).isEqualTo(30);
        assertThat(first.packsPerPallet()).isEqualTo(72);
        assertThat(first.vatPercent()).isEqualTo(5);
        assertThat(first.guaranteedDays()).isEqualTo(11);
        assertThat(first.purchasePrice()).isEqualByComparingTo(new BigDecimal("6.03"));
        assertThat(first.shelfPrice()).isEqualByComparingTo(new BigDecimal("8.99"));
        assertThat(first.margin()).isEqualByComparingTo(new BigDecimal("28.16"));
        assertThat(first.parallelItm8()).isNull();
    }
}
