package com.intermanage.backend.document.dto;

import java.time.Instant;

/** Plik dołączony do faktury spoza KSeF (bez treści, ta jest pod osobnym adresem). */
public record ManualInvoiceFile(String fileName, String contentType, int sizeBytes, Instant uploadedAt) {
}
