package com.intermanage.backend.collector.dto;

/** Nowa albo poprawiona drukarka: adres MAC z dwukropkami, kreskami albo bez; model i PIN opcjonalne. */
public record StorePrinterRequest(String name, String mac, String model, String pin) {
}
