package com.intermanage.backend.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Makra na komputerach sklepu: żądania i odpowiedzi agenta i przeglądarki. */
public final class AgentMacroApi {

    private AgentMacroApi() {
    }

    // --- Agent ---

    /** Stan komputera przy czekaniu na makra. */
    public record Poll(Integer idleSeconds, Boolean locked, Boolean playerRunning, Boolean busy,
            @Size(max = 20) String playerVersion) {
    }

    /**
     * Czekanie na makro (od agenta 1.9.0): stan komputera, {@code handled} = makro, które agent już odlicza albo
     * wykonuje, {@code timeout} w sekundach.
     */
    public record Wait(Integer idleSeconds, Boolean locked, Boolean playerRunning, Boolean busy,
            @Size(max = 20) String playerVersion, UUID handled, Integer timeout) {

        public Poll state() {
            return new Poll(idleSeconds, locked, playerRunning, busy, playerVersion);
        }
    }

    /** Makro zaproponowane temu komputerowi albo {@code null}. */
    public record PollResponse(Offer offer) {
    }

    /** {@code holdOnInput}: ruch myszy albo klawiatury zaczyna odliczanie od nowa (agent od 1.11.0). */
    public record Offer(UUID id, String title, String requestedBy, int countdownSeconds, boolean holdOnInput) {
    }

    /**
     * Treść makra po odliczaniu; {@code openLine}: pierwsze polecenie, do którego agent dokłada hasło Mercalys;
     * {@code requiredFiles}: pliki, które przed startem muszą leżeć w folderze (sprawdza agent od 1.10.0).
     */
    public record Start(String macro, String openLine, String kind, boolean collectsCodes, int timeoutMinutes,
            List<Upload> uploads, List<String> requiredFiles) {
    }

    /** Plik zapisany przez makro, który agent wysyła po nim ({@code POST .../macro-jobs/{id}/files}). */
    public record Upload(String key, String path) {
    }

    public record Decline(@Size(max = 500) String reason) {
    }

    public record Finish(@NotNull Boolean ok, @Size(max = 4000) String message, @Size(max = 5000) List<String> codes) {
    }

    // --- Przeglądarka ---

    public record Computer(UUID id, String name, String machineName, boolean online, boolean available, boolean free,
            Integer idleSeconds, String reason) {
    }

    public record Itm8CollectRequest(@NotNull UUID deliveryId, UUID agentId) {
    }

    public record Itm8ApplyRequest(@NotNull UUID deliveryId, @NotNull @Size(min = 1, max = 2000) List<String> codes,
            UUID agentId) {
    }

    /** Kody MEA z listy „Do wpisania” sklepu. */
    public record MeaCodesRequest(@NotNull @Size(min = 1, max = 200, message = "Zaznacz od 1 do 200 kodów MEA")
    List<@NotNull String> codes, UUID agentId) {
    }

    /** Bon dostawy z bazy („Wprowadź dostawę” i „Zatwierdź”). */
    public record BaseDeliveryRequest(@NotNull UUID deliveryId, UUID agentId) {
    }

    /** {@code expected}: plik nowych artykułów wczyta komputer sklepu, więc o nic nie pytamy. */
    public record ProductFileExpected(boolean expected) {
    }

    public record Job(UUID id, String kind, String title, String status, String computer, String requestedBy,
            Instant createdAt, Instant offeredAt, Instant startedAt, Instant finishedAt, int countdownSeconds,
            List<String> declined, String message, List<String> codes, int itemCount, String reference) {
    }

    /** Co robią komputery sklepu: trwające i ostatnie makra (od najnowszego) i trwająca aktualizacja danych. */
    public record Activity(List<Job> jobs, Update update) {
    }

    public record Update(String computer, Instant startedAt) {
    }
}
