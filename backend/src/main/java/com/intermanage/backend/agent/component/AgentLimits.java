package com.intermanage.backend.agent.component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Czasy i limity agenta (od 1.13.0): idą w odpowiedzi {@code /hello}, więc zmienia się je tutaj, wdrożeniem serwera, bez
 * nowej wersji programu na komputerach sklepów (agent bierze je do 5 min). Agent trzyma każdą wartość w swoich granicach
 * ({@code agent/src/Limits.cs}), a nazwy, której nie zna, nie używa.
 */
public final class AgentLimits {

    public static final Map<String, Integer> VALUES = new TreeMap<>(Map.ofEntries(
            // Aktualizacja danych z Mercalys: ile makr, po jakim czasie bez zmian koniec, czekanie na odtwarzacz i foldery.
            Map.entry("updateRounds", 3),
            Map.entry("updateGiveUpSeconds", 5400),
            Map.entry("playerWaitSeconds", 180),
            Map.entry("updateFolderWaitSeconds", 600),
            // Makra zlecone: kiedy komputer jest używany, najdłuższe czekanie na chwilę bez ruchu (serwer czeka 30 s).
            Map.entry("inUseSeconds", 120),
            Map.entry("holdSeconds", 20),
            Map.entry("serverRetrySeconds", 20),
            Map.entry("macroPickUpSeconds", 15),
            Map.entry("playerGoneSeconds", 20),
            // Raporty z Excela: tyle bez zmian pliku, zanim pójdzie, i tyle czekania na koniec zapisu.
            Map.entry("reportSteadySeconds", 2),
            Map.entry("reportSaveSeconds", 60),
            // Ceny z kasy: wielkość jednej wysyłki (wolne łącze) i pełny przegląd folderów.
            Map.entry("priceFilesPerUpload", 10),
            Map.entry("priceKilobytesPerUpload", 2048),
            Map.entry("folderScanSeconds", 300)));

    private AgentLimits() {
    }
}
