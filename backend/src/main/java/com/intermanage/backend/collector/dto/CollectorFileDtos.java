package com.intermanage.backend.collector.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.collector.entity.CollectorFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Inwentaryzacje i zamówienia z kolektora w historii. */
public final class CollectorFileDtos {

    private CollectorFileDtos() {
    }

    /** {@code lineCount}: artykułów w pliku; {@code skipped}: pominiętych przy tworzeniu (bez EAN-u, ujemna ilość). */
    public record Response(UUID id, CollectorFile.Kind kind, CollectorFile.Status status, String note, String fileName,
            Instant createdAt, String createdBy, Instant loadedAt, String loadedBy, long lineCount) {
    }

    public record Created(Response file, int skipped) {
    }

    /**
     * Nowa pozycja historii z list kolektora. Inwentaryzacja bierze ilości z list, zamówienie z {@code lines}
     * (kartony poprawione w oknie). Plik powstaje osobno ({@code /file}), a zapisanie go potwierdza {@code /loaded}.
     */
    public record CreateRequest(@NotNull CollectorFile.Kind kind, List<UUID> listIds,
            @Size(max = 200) String note, List<@Valid OrderLine> lines,
            @Size(max = 5000) List<@Valid Item> items) {
    }

    /**
     * Artykuł inwentaryzacji spoza listy kolektora (tabela wprowadzania danych albo Protokołów): bez list
     * ({@code listIds} puste) inwentaryzacja bierze ilości stąd.
     */
    public record Item(String itm8, @NotNull String ean, @NotNull String name, String brand,
            @NotNull BigDecimal quantity) {
    }

    /** Kartony zamówienia. */
    public record OrderLine(@NotNull String ean, @NotNull BigDecimal quantity) {
    }

    public record LoadedRequest(@NotNull @Size(min = 1, max = 200) String fileName) {
    }
}
