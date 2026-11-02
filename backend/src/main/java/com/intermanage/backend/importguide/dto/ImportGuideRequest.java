package com.intermanage.backend.importguide.dto;

import jakarta.validation.constraints.Size;

/** Instrukcja do raportu; pusta treść i ścieżka usuwają instrukcję (znacznik). */
public record ImportGuideRequest(
        @Size(max = 4000, message = "Najwyżej 4000 znaków")
        String content,
        @Size(max = 500, message = "Najwyżej 500 znaków")
        String folderPath) {
}
