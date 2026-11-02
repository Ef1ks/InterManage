package com.intermanage.backend.product.dto;

import java.util.List;

/** Wynik wysłania plików TRACEART: wczytane teraz i pominięte, bo serwer już je miał. */
public record PriceFileUploadResponse(List<PriceFileImportResponse> imported, List<String> skipped) {
}
