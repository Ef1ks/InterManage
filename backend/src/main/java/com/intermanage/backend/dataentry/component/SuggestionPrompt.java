package com.intermanage.backend.dataentry.component;

import java.util.List;
import java.util.Map;

/**
 * Treść zapytania do modelu. Stała część (zasady i całe drzewo nomenklatury sklepu) idzie w instrukcji systemowej, na
 * początku zapytania, żeby Gemini mógł ją buforować; zmienna część to pozycje z wskazówkami z kartoteki.
 */
public final class SuggestionPrompt {

    private SuggestionPrompt() {
    }

    private static final String RULES = """
            Zakładasz nowe artykuły w kartotece supermarketu Intermarché na podstawie pozycji z faktury dostawcy.
            Dla każdej pozycji zwróć:

            - subfamily: 6-cyfrowy kod podrodziny, wyłącznie z drzewa nomenklatury poniżej.
            - alternatives: do 4 innych prawdopodobnych podrodzin, od najbardziej prawdopodobnej (mogą być puste).
            - confidence: 0-1, jak bardzo jesteś pewien podrodziny.
              Wskazówki do podrodziny, od najmocniejszej:
              1. "ten sam producent (prefiks EAN)": inne produkty tego producenta w kartotece i ich podrodziny.
                 Producent zwykle robi jeden rodzaj towaru, ale sprawdź, czy ten produkt pasuje do tej samej grupy.
              2. "podobne nazwy w kartotece": gdzie sklep trzyma produkty o podobnej nazwie.
            - name: nazwa artykułu dla klienta, najwyżej 30 znaków:
              * jak najbardziej czytelna: pełne słowa, skróty tylko wtedy, gdy bez nich nazwa się nie mieści;
              * WIELKIE LITERY, bez polskich znaków, bez kropek (ułamek z przecinkiem);
              * bez marki i bez producenta (marka idzie osobno), bez opisu kartonu (np. "/12", "8/");
              * na końcu miara sprzedawanej sztuki: poniżej 1 kg gramy (250G), od 1 kg kilogramy (1KG, 1,5KG),
                poniżej 1 l mililitry (330ML, 900ML), od 1 l litry (1L, 1,5L), wielopak 5X14G;
                towar bez gramatury i pojemności bez miary.
              Przykłady: "Ferrero Ciastka Kinder DUO 150g/12" -> "CIASTKA DUO 150G";
              "Mleko UHT Wypasione o smak.trusk.200ml" -> "MLEKO UHT TRUSKAWKOWE 200ML";
              "WAFI | Krem dla kota - kurczak [5x14g]" -> "KREM DLA KOTA KURCZAK 5X14G".
            - brand: MARKA produktu, nie producent ani dystrybutor: Milka (nie Mondelez), Kinder (nie Ferrero),
              WIELKIE LITERY. Jeśli marka jest już w kartotece (podobne nazwy, ten sam producent), zapisz ją tak samo.
              Gdy marki nie da się ustalić, pusty tekst.
            - unit i net_content opisują sprzedawaną sztukę, nie jednostkę z faktury:
              gramatura -> unit "kg" i net_content w kg (150 g -> 0.15), pojemność -> "L" (330 ml -> 0.33),
              wielopak -> suma (5x14 g -> 0.07), towar na wagę -> "kg" i 1, bez gramatury i pojemności -> "szt." i 1.
              Opis kartonu ("8/360 g" = karton 8 sztuk po 360 g) nie zmienia sztuki: 0.36 kg.
            Zwróć wynik dla każdej pozycji, z jej id.

            Drzewo nomenklatury sklepu (rejon > rodzina > podrodzina):
            """;

    /** Instrukcja systemowa: zasady i drzewo nomenklatury ({@code code -> name}, kody 2, 4 i 6 cyfr). */
    public static String systemInstruction(Map<String, String> nomenclature) {
        StringBuilder text = new StringBuilder(RULES);
        nomenclature.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> text
                .append("  ".repeat(entry.getKey().length() / 2 - 1))
                .append(entry.getKey()).append(' ').append(entry.getValue()).append('\n'));
        return text.toString();
    }

    /** Pozycja z wskazówkami z kartoteki; {@code producer} puste, gdy EAN nie wskazuje producenta. */
    public record Item(String invoiceName, String ean, String invoiceUnit, List<ProducerGroup> producer,
            int producerProducts, List<Similar> similar) {
    }

    public record ProducerGroup(String subfamily, int count, List<String> examples) {
    }

    public record Similar(String name, String brand, String subfamily) {
    }

    public record Example(String invoiceName, String catalogName, String brand) {
    }

    /** Pozycje paczki z numerami {@code id} od 0, poprzedzone przykładami nazw tego dostawcy. */
    public static String prompt(String supplier, List<Example> examples, List<Item> items) {
        StringBuilder text = new StringBuilder();
        if (supplier != null && !supplier.isBlank()) {
            text.append("Dostawca: ").append(supplier.strip()).append('\n');
        }
        if (!examples.isEmpty()) {
            text.append("Jak ten dostawca nazywa artykuły, które sklep już ma (faktura -> kartoteka | marka):\n");
            examples.forEach(example -> text.append("  ").append(example.invoiceName()).append("  ->  ")
                    .append(example.catalogName()).append(" | ").append(orDash(example.brand())).append('\n'));
        }
        for (int id = 0; id < items.size(); id++) {
            Item item = items.get(id);
            text.append("\n### Pozycja id=").append(id).append('\n')
                    .append("faktura: ").append(item.invoiceName())
                    .append(" (EAN ").append(orDash(item.ean()))
                    .append(", jedn. ").append(orDash(item.invoiceUnit())).append(")\n");
            if (!item.producer().isEmpty()) {
                text.append("ten sam producent (prefiks EAN, ").append(item.producerProducts())
                        .append(" produktów w kartotece):\n");
                item.producer().forEach(group -> text.append("  ").append(group.subfamily()).append(" x")
                        .append(group.count())
                        .append(group.examples().isEmpty() ? "" : "  np. " + String.join("; ", group.examples()))
                        .append('\n'));
            }
            if (!item.similar().isEmpty()) {
                text.append("podobne nazwy w kartotece (nazwa | marka | podrodzina):\n");
                item.similar().forEach(similar -> text.append("  ").append(similar.name()).append(" | ")
                        .append(orDash(similar.brand())).append(" | ").append(similar.subfamily()).append('\n'));
            }
        }
        return text.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }
}
