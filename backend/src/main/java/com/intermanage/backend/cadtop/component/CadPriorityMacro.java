package com.intermanage.backend.cadtop.component;

import java.util.ArrayList;
import java.util.List;

import com.intermanage.backend.importguide.component.ReportSaveMacro;

/**
 * Makro ustawiające ITM8 z cadencier jako priorytetowy na karcie artykułu. Składa je serwer, bo docelowo ma iść rano na
 * komputerze sklepu razem z aktualizacją danych (agent wykonuje tylko makra z serwera).
 *
 * <p>Kroki ekranów Mercalys jeszcze nie są znane ({@link #READY} = false): po ich otrzymaniu wystarczy wypełnić
 * {@link #steps} i włączyć {@link #READY}.
 */
public final class CadPriorityMacro {

    public static final boolean READY = false;

    /** {@code ean}: kod karty w sklepie, {@code itm8}: jej obecny ITM8, {@code topItm8}: ten, który ma być priorytetowy. */
    public record Item(String ean, String itm8, String topItm8) {
    }

    private CadPriorityMacro() {
    }

    public static String build(List<Item> items) {
        List<String> out = new ArrayList<>();
        out.add("# ITM8 priorytetowe z cadencier: " + items.size());
        out.add(ReportSaveMacro.OPEN_LINE);
        for (Item item : items) {
            out.add("# " + item.ean() + ": " + item.itm8() + " -> " + item.topItm8());
            out.addAll(steps(item));
        }
        out.add("WINMIN|" + ReportSaveMacro.PROGRAM_WINDOW);
        return String.join("\r\n", out) + "\r\n";
    }

    private static List<String> steps(Item item) {
        return List.of();
    }
}
