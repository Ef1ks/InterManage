package com.intermanage.backend.collector.dto;

import java.time.Instant;
import java.util.UUID;

/** Drukarka Bluetooth sklepu (Kolektory, Urządzenia); {@code mac} bez dwukropków, wielkimi literami. */
public record StorePrinterResponse(UUID id, String name, String mac, String model, String pin, Instant createdAt,
        String createdBy, Instant deletedAt) {
}
