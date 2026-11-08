package com.intermanage.backend.download.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nazwa i opis pozycji „Do pobrania”. */
public record DownloadRequest(
        @NotBlank(message = "Podaj nazwę") @Size(max = 120, message = "Nazwa może mieć najwyżej 120 znaków") String title,
        @Size(max = 2000, message = "Opis może mieć najwyżej 2000 znaków") String description) {
}
