package com.intermanage.backend.dataentry.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.dataentry.entity.SupplierRuleMode;

/** Dostawca z regułą okna wczytywania faktur: pomijany albo odkładany do wczytanych. */
public record SkippedSupplier(UUID id, String sellerNip, String sellerName, SupplierRuleMode mode, Instant createdAt) {
}
