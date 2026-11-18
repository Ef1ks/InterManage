package com.intermanage.backend.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Początek wysyłki pliku w kawałkach: nazwa i rozmiar w bajtach. */
public record ImportUploadRequest(@NotBlank @Size(max = 255) String fileName, @Positive long size) {
}
