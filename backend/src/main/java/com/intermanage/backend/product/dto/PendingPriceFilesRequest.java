package com.intermanage.backend.product.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nazwy plików TRACEART z folderu przeglądarki: serwer odpowiada, których jeszcze nie wczytał. */
public record PendingPriceFilesRequest(@NotNull @Size(max = 5000) List<String> fileNames) {
}
