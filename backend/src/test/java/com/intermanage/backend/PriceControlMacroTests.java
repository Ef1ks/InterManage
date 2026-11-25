package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.PriceControlMacro;

/** Te same linie co {@code buildPriceControlMacro} we froncie (ta sama nazwa pliku i etykieta). */
class PriceControlMacroTests {

    @Test
    void sameStepsAsMacroFromBrowser() {
        String macro = PriceControlMacro.build(List.of(new PriceControlMacro.File(
                "CTRLPRIX_20261124_110254_DORA_admin.traite0", 14, "DORA | x · FV/1")));
        assertThat(macro.lines()).startsWith(
                "# Wprowadzenie zmian cen: 1 (odtwarzacz od wersji 0.60)",
                "WINOPEN|Mercalys ahk_exe Mercalys.exe",
                "# 1/1: 24.11.2026 11:02, cen: 14, DORA   x · FV/1",
                "WEBENSURE|Mercalys|mnTv_TARIF_ControlePrix|mnTp_TARIF",
                "WEBOPEN|Mercalys|mnTv_TARIF_ControlePrix|mnTv_TARIF_ar-e083_Dechargement|btnHistorique_LnkBouton",
                "WEBWAIT|Mercalys|btnHistorique_LnkBouton||30000",
                "WEBCLICK|Mercalys|btnHistorique_LnkBouton",
                "WEBWAIT|Mercalys|btnRetourHisto_LnkBouton||30000",
                "WEBROWBUTTON|Mercalys|dgHisto|i)^(?=.*(?<!\\d)24[./-]11[./-](?:20)?26(?!\\d))"
                        + "(?=.*(?<!\\d)11:0[23](?!\\d))|ImgButtonTraiter_ImageButton1|zmiana-cen-1|"
                        + "24.11.2026 11:02, cen: 14, DORA   x · FV/1|pgrDetail_ibtSuivant|60|30000",
                "WEBWAIT|Les Mousquetaires|Oui|ponownie|30000",
                "WEBCLICK|Les Mousquetaires|Oui",
                "WINCLOSEALL|ahk_class AcrobatSDIWindow|120000");
        assertThat(macro.lines()).endsWith(
                "WEBCLICK|Mercalys|btnB6_AlignerMSI_LnkBouton",
                "WEBDIALOG|ahk_class Internet Explorer_TridentDlgFrame|promocji|Fermer|15000",
                "WEBGRIDWAIT|Mercalys|btnB6_AlignerMSI_LnkBouton|page|i)Suma wyboru\\s*:\\s*0(?!\\d)"
                        + "|zmiana-cen-1-bez-roznic||120000",
                "LABEL|zmiana-cen-1-bez-roznic",
                "LABEL|zmiana-cen-1",
                "WINMIN|Mercalys ahk_exe Mercalys.exe");
    }

    @Test
    void timeAndLastMinute() {
        assertThat(PriceControlMacro.time("ctrlprix_20261124_110254_dora_admin"))
                .isEqualTo(LocalDateTime.of(2026, 11, 24, 11, 2, 54));
        assertThat(PriceControlMacro.time("PROTO1_20261124_110254_10023_admin.Traite0")).isNull();
        assertThat(PriceControlMacro.time("CTRLPRIX_20261399_110254_X_admin.traite0")).isNull();
        assertThat(PriceControlMacro.build(List.of(new PriceControlMacro.File(
                "CTRLPRIX_20261124_142930_X_admin.traite0", 1, "X")))).contains("(?=.*(?<!\\d)14:29(?!\\d))");
    }
}
