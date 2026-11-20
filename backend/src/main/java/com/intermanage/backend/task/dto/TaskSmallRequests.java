package com.intermanage.backend.task.dto;

import jakarta.validation.constraints.Size;

/** Małe żądania zadań: komentarz do wykonania albo odesłania i odhaczenie pozycji. */
public final class TaskSmallRequests {

    private TaskSmallRequests() {
    }

    public record Comment(@Size(max = 2000, message = "Komentarz może mieć najwyżej 2000 znaków") String comment) {
    }

    public record ItemCheck(boolean checked,
            @Size(max = 500, message = "Uwaga może mieć najwyżej 500 znaków") String note) {
    }
}
