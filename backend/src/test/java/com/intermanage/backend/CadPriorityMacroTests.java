package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.cadtop.component.CadPriorityMacro;
import com.intermanage.backend.cadtop.component.CadPriorityMacro.Item;

/** Makro ITM8 z cadencier: kroki ze skanu ekranów Mercalys, przeskoki i odczyt wyniku. */
class CadPriorityMacroTests {

    private final String macro = CadPriorityMacro.build(List.of(
            new Item("5900983005014", "0000000000604", "0000061509966"),
            new Item("2485390000000", "0000099000800", "0000000000800")));

    @Test
    void setsPriorityAndActivityOnCard() {
        assertThat(macro).startsWith("# ITM8 z cadencier, priorytet i aktywność: 2\r\nWINOPEN|Mercalys ahk_exe Mercalys.exe\r\n")
                .endsWith("WINMIN|Mercalys ahk_exe Mercalys.exe\r\n");
        assertThat(List.of(macro.split("\r\n"))).containsSubsequence(
                "WEBKEY|Mercalys|txtZ1_CodeITM_TxtBox|^a604{Enter}",
                "WEBIFVALUE|Mercalys|txtZ4_CodeEAN_TxtBox|^0*5900983005014$|inny-ean-604|3000",
                "WEBCLICK|Mercalys|lnkZ1_CodeITM_LnkAviso",
                "WEBKEY|Mercalys|txtZ02_Itm_TxtBox|^a61509966{Tab}",
                "WEBIFVALUE|Mercalys|lstZ04_Fournisseur_TxtBox|^0*[1-9]\\d*$|brak-itm8-604|8000",
                "WEBCHECK|Mercalys|chkZ03_Prioritaire_CheckBox1",
                "WEBCHECK|Mercalys|chkZ10_Actif_CheckBox1",
                "WEBVALUE|Mercalys|chkZ03_Prioritaire_CheckBox1|^tak$",
                "WEBVALUE|Mercalys|chkZ10_Actif_CheckBox1|^tak$",
                "WEBCLICK|Mercalys|btnB4_Valider_LnkBouton",
                "IFDIALOG|Les Mousquetaires|zachowa|Oui|nowy-604|5000",
                "WEBCLICK|Mercalys|btnB1_Retour_LnkBouton",
                "WEBCLICK|Mercalys|btnB2_RAZ_LnkBouton",
                "LABEL|koniec-604",
                "WEBKEY|Mercalys|txtZ1_CodeITM_TxtBox|^a99000800{Enter}");
    }

    /** „Kody CN, GTU i Split Payment są wymagane” po wczytaniu karty: kod CN, zapis karty i dopiero ITM8. */
    @Test
    void entersCnCodeWhenCardRequiresIt() {
        assertThat(List.of(macro.split("\r\n"))).containsSubsequence(
                "WEBKEY|Mercalys|txtZ1_CodeITM_TxtBox|^a604{Enter}",
                "IFDIALOG|okno dialogowe strony|Kody CN|Fermer|cn-jest-604|500",
                "WEBIFVALUE|Mercalys|txtZ4_CodeEAN_TxtBox|^0*5900983005014$|inny-ean-604|3000",
                "WEBCLICK|Mercalys|Lnk_CodeCN_LnkAviso",
                "WEBPAGE|Mercalys|Chrono=|btnB1_Retour_LnkBouton|30000",
                "KEY|^a99999980{Enter}",
                "KEY|{Enter}",
                "WEBDIALOG|Les Mousquetaires|zachowa;Utworzy|Oui|10000",
                "WEBCLICK|Mercalys|btnB1_Retour_LnkBouton",
                "WEBIFVALUE|Mercalys|txt_CodeCn_TxtBox|^\\s*99999980\\s*$|brak-cn-604|5000",
                "WEBCLICK|Mercalys|btnB6_Valider_LnkBouton",
                "IFDIALOG|Les Mousquetaires|zachowa|Oui|brak-cn-604|30000",
                "LABEL|cn-jest-604",
                "WEBCLICK|Mercalys|lnkZ1_CodeITM_LnkAviso");
    }

    /** Odtwarzacz szuka etykiety tylko w przód, a linie dzieli po znaku |. */
    @Test
    void everyJumpHasLaterLabel() {
        List<String> lines = List.of(macro.split("\r\n"));
        Set<String> commands = new HashSet<>(Set.of("WEBIFVALUE", "IFDIALOG", "WEBIFSEEN"));
        for (int index = 0; index < lines.size(); index++) {
            String[] parts = lines.get(index).split("\\|");
            if (commands.contains(parts[0])) {
                String label = parts[parts[0].equals("WEBIFSEEN") ? 3 : 4];
                assertThat(lines.subList(index + 1, lines.size())).contains("LABEL|" + label);
            }
        }
        assertThat(Arrays.stream(macro.split("\r\n")).filter(line -> line.startsWith("LABEL|")).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void readsSkippedAndCompletedFromResult() {
        assertThat(CadPriorityMacro.skipped("Pominięto 604: ITM8 61509966 nie ma jeszcze w Mercalys\r\n"
                + "Pominięto 99000800: na karcie jest inny EAN niż w kartotece\r\n"))
                .isEqualTo(Map.of("604", "ITM8 61509966 nie ma jeszcze w Mercalys",
                        "99000800", "na karcie jest inny EAN niż w kartotece"));
        List<String> lines = List.of(macro.split("\r\n"));
        // Numer linii odtwarzacza: od 1, z linią identyfikatora dołożoną przez agenta.
        int secondCard = lines.indexOf("WEBKEY|Mercalys|txtZ1_CodeITM_TxtBox|^a99000800{Enter}") + 2;
        assertThat(CadPriorityMacro.completed(macro, "Nie ma pola.\n\nMakro zatrzymane na linii " + secondCard + ":"))
                .containsExactly("604");
        int firstCard = lines.indexOf("LABEL|koniec-604") + 1;
        assertThat(CadPriorityMacro.completed(macro, "Makro zatrzymane na linii " + firstCard + ":")).isEmpty();
        assertThat(CadPriorityMacro.completed(macro, "Komputer przestał odpowiadać")).isEmpty();
    }
}
