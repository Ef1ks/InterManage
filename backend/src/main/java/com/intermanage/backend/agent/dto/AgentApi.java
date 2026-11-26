package com.intermanage.backend.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.download.dto.AgentUpdateInfo;
import com.intermanage.backend.importguide.component.ReportSaveMacro;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Żądania i odpowiedzi API agenta sklepu ({@code /api/store-agent/**}). */
public final class AgentApi {

    private AgentApi() {
    }

    /** Agent przy starcie: wersja programu i komputer, na którym działa. */
    public record Hello(@Size(max = 50) String version, @Size(max = 100) String machineName,
            @Size(max = 100) String user) {
    }

    /**
     * Konfiguracja agenta: foldery zapisu (klucz folderu → ścieżka) i foldery do pilnowania. {@code enabled}: sklep
     * wybrał pracę przez agenta (bez tego agent tylko utrzymuje kontakt). {@code leader}: ten agent jest głównym agentem
     * sklepu, więc pilnuje folderów i aktualizuje dane z Mercalys (każda odpowiedź API niesie to też w nagłówku
     * {@code X-Agent-Leader}); {@code preferred}: agent preferowany, który odbiera tę rolę pozostałym.
     * {@code update}: obowiązująca wersja agenta z „Do pobrania” (brak = nie ma paczki); agent od 1.14.0 tylko o niej
     * mówi, a pobiera i podmienia ją człowiek (starsze pobierały ją z {@code /update/file}). {@code cleanProductFiles}:
     * agent od 1.12.2 czyści folder nowych artykułów z plików z poprzednich dni. {@code limits}: czasy i limity agenta
     * od 1.13.0 ({@code AgentLimits}). {@code mercalysPath}: program Mercalys sklepu (ścieżka sklepu
     * {@code mercalys:path} albo domyślna), z którego odtwarzacz makr uruchamia wyłączony Mercalys (agent od 1.14.0).
     */
    public record HelloResponse(UUID storeId, String storeNumber, String storeName, String agentName, boolean enabled,
            Map<String, String> folders, List<Watch> watches, int longPollSeconds, boolean leader,
            boolean preferred, AgentUpdateInfo update, boolean cleanProductFiles, Map<String, Integer> limits,
            String mercalysPath) {
    }

    /**
     * Folder do pilnowania: {@code kind} TRACEART, CTRLPRIX albo ATHOS, {@code pattern} = wyrażenie na nazwę pliku
     * (bez wielkości liter), {@code subfolders} = także podfoldery (np. BACKUP). {@code freshPattern} (raporty Importu
     * danych): plik, którego dzisiejsza data zmiany znaczy, że raport jest aktualny, czyli plik zapisywany przez makro;
     * inny plik w folderze (np. raport zapisany ręcznie pod domyślną nazwą Mercalys) nie zwalnia z aktualizacji.
     */
    public record Watch(String kind, String path, boolean subfolders, String pattern, String freshPattern) {

        public Watch(String kind, String path, boolean subfolders, String pattern) {
            this(kind, path, subfolders, pattern, null);
        }
    }

    /** Plik z kolejki przydzielony agentowi. */
    public record QueueFile(UUID id, String folderKey, String fileName, int size, Instant createdAt) {
    }

    public record QueueFiles(List<QueueFile> files) {
    }

    public record Failed(@Size(max = 2000) String error) {
    }

    /**
     * Zdarzenie z folderu. {@code kind}: CREATED, CHANGED, RENAMED, DELETED albo PRESENT (pełny przegląd),
     * {@code folderKey}: rodzaj pilnowanego folderu (TRACEART, CTRLPRIX, ATHOS), {@code modified}: data zmiany pliku.
     */
    public record Event(@NotBlank @Size(max = 30) String kind, @NotBlank @Size(max = 30) String folderKey,
            @NotBlank @Size(max = 260) String fileName, Long size, Instant modified, Instant at) {
    }

    public record Events(@NotNull @Size(max = 5000) List<@Valid Event> events) {
    }

    /**
     * Pliki, o które prosi serwer (agent wysyła je od razu): TRACEART do {@code /price-files}, ATHOS do
     * {@code /athos-history}, raporty importu ({@code IMPORT_…}) w kawałkach przez {@code /reports}. {@code deletes}:
     * wgrane wcześniej raporty do usunięcia z folderu.
     */
    public record EventsResponse(List<FileRequest> requests, List<FileDelete> deletes) {

        public EventsResponse(List<FileRequest> requests) {
            this(requests, List.of());
        }
    }

    public record FileRequest(String folderKey, String fileName) {
    }

    /**
     * Wgrany raport do usunięcia z folderu. Agent usuwa plik tylko, gdy ma dokładnie tę datę zmiany ({@code modified}),
     * czyli nie nowszy plik pod tą samą nazwą.
     */
    public record FileDelete(String folderKey, String fileName, Instant modified) {
    }

    /** Początek wysyłki raportu z folderu: rodzaj folderu ({@code IMPORT_…}), plik i jego data zmiany na dysku. */
    public record ReportStart(@NotBlank @Size(max = 30) String folderKey, @NotBlank @Size(max = 260) String fileName,
            @NotNull Long size, @NotNull Instant modified) {
    }

    /** Wysyłka raportu: {@code id} próby wgrania, kawałki po {@code chunkSize} bajtów. */
    public record ReportUpload(UUID id, int chunkSize) {
    }

    /** Agent usunął wgrany raport z folderu. */
    public record ReportRemoved(@NotBlank @Size(max = 30) String folderKey, @NotBlank @Size(max = 260) String fileName,
            @NotNull Instant modified) {
    }

    /**
     * Raporty do aktualizacji z Mercalys (pliki w folderach nie są z dzisiaj); pusta lista = wszystkie z makrem.
     * {@code automatic}: aktualizacja przy starcie komputera albo po przejęciu roli głównego agenta (tylko główny
     * agent); {@code false} = pracownik wybrał ją w menu ikony. Brak (agent przed 1.3) = automatyczna.
     * {@code mercalys}: na ilu Mercalys naraz (ustawienie komputera, agent od 1.14.0, odtwarzacz od 0.73); brak = 1.
     */
    public record ReportMacroRequest(@Size(max = 20) List<@NotBlank @Size(max = 30) String> kinds,
            Boolean automatic, @Min(1) @Max(ReportSaveMacro.MAX_LANES) Integer mercalys) {
    }

    /** Wynik aktualizacji danych z Mercalys (agent od 1.13.0); {@code ok} puste = nieznany. */
    public record UpdateFinished(Boolean ok, @Size(max = 4000) String message) {
    }
}
