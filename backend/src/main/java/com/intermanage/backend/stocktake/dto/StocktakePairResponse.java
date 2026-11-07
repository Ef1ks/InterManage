package com.intermanage.backend.stocktake.dto;

/** Dwa podobne artykuły z przeciwnymi różnicami: pewnie policzono jeden zamiast drugiego. */
public record StocktakePairResponse(String plusItm8, String minusItm8, StocktakeReason strength) {
}
