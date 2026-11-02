package com.intermanage.backend.product.dto;

import java.util.List;

/** Pliki TRACEART do wysłania (jeszcze nie wczytane), w kolejności od najstarszego. */
public record PendingPriceFilesResponse(List<String> fileNames) {
}
