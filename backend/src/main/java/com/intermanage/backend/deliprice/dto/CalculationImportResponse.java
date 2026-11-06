package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

/** Kalkulacje bez przepisu założone importem ({@code ids}, do cofnięcia) i wyroby pominięte, bo już mają kalkulację. */
public record CalculationImportResponse(List<UUID> ids, int skipped) {
}
