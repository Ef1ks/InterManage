package com.intermanage.backend.importguide.dto;

import java.util.List;

/**
 * Makro zapisu raportów z Mercalys dla odtwarzacza. {@code openLine} to pierwsze polecenie makra: klient dokłada do
 * niego hasło Mercalys ({@code |hasło}), gdy je zna. {@code files} to pliki, które makro zapisze (części okresu po
 * kolei), {@code skipped} raporty bez ustawień z powodem.
 */
public record ReportMacroResponse(String macro, String openLine, List<String> reports, List<Skipped> skipped,
        List<SavedFile> files, String playerVersion) {

    public record Skipped(String kind, String title, String reason) {
    }

    public record SavedFile(String kind, String title, String fileName) {
    }
}
