package com.intermanage.backend.product.dto;

import java.util.List;

/** Ile kodów (par ITM8 + EAN) i artykułów jest w najnowszych multikodach sklepu i ostatnie wgrania raportu. */
public record ProductCodeImportOverview(long codeCount, long articleCount, List<ProductCodeImportResponse> imports) {
}
