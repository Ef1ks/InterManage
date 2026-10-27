package com.intermanage.backend.common.component;

import java.text.Normalizer;
import java.util.Locale;

/** Tekst z raportów systemu sklepu w postaci do porównań (nagłówki kolumn, stopki), wspólny dla parserów. */
public final class ReportText {

    private ReportText() {
    }

    /**
     * Nazwa kolumny w postaci porównywalnej: "Numéro document interne" -> "numero document interne". Litera "ł"
     * nie rozkłada się na znak i akcent, więc zamieniamy ją osobno ("Słowo klucz" -> "slowo klucz").
     */
    public static String normalize(String value) {
        String plain = Normalizer.normalize(value.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
