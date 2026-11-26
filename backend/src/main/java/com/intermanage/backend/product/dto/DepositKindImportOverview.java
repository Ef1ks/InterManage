package com.intermanage.backend.product.dto;

import java.util.List;

/** Opakowania zwrotne sklepu i ostatnie wgrania ich listy. */
public record DepositKindImportOverview(List<DepositKindResponse> kinds, List<DepositKindImportResponse> imports) {
}
