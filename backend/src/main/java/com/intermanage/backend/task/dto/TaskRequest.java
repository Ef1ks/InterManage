package com.intermanage.backend.task.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.task.entity.TaskPriority;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nowe zadanie albo jego zmiana. Źródło i pozycje liczą się tylko przy zakładaniu: pozycje to dane z chwili zlecenia,
 * a źródło to widok, z którego zadanie powstało.
 */
public record TaskRequest(
        @NotBlank(message = "Wpisz, co trzeba zrobić") @Size(max = 200, message = "Tytuł może mieć najwyżej 200 znaków")
        String title,
        @Size(max = 4000, message = "Opis może mieć najwyżej 4000 znaków") String description,
        LocalDate dueDate,
        @NotNull(message = "Wybierz priorytet") TaskPriority priority,
        /** Jeden wykonawca (zmiana zadania i starsze aplikacje). */
        UUID assigneeId,
        /** Kilka osób naraz: każda dostaje swoją kopię (tylko przy zakładaniu). */
        @Size(max = 100, message = "Zadanie zlecisz naraz najwyżej 100 osobom") List<UUID> assigneeIds,
        /** Zadanie dla całego sklepu: wykonuje ktokolwiek z dostępem do sklepu. */
        Boolean forStore,
        @Size(max = 60) String sourceKind,
        @Size(max = 200) String sourceLabel,
        // Tylko ścieżka w aplikacji: z zadania nie da się zrobić odnośnika na obcą stronę (//host, javascript:).
        @Size(max = 2000) @Pattern(regexp = "/(?![/\\\\]).*", message = "Miejsce zlecenia musi być stroną aplikacji")
        String sourceUrl,
        @Size(max = 500, message = "Zadanie może mieć najwyżej 500 pozycji") List<@Valid Item> items) {

    public record Item(
            @Size(max = 20) String itm8,
            @Size(max = 30) String ean,
            @NotBlank(message = "Pozycja musi mieć nazwę") @Size(max = 300) String name,
            @Size(max = 500) String details) {
    }
}
