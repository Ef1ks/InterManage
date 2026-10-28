package com.intermanage.backend.pricecheck.dto;

import java.util.List;

/** Ile artykułów jest teraz w cadencier sklepu i ostatnie wgrania listy (od najnowszego). */
public record CadencierOverview(long itemCount, List<CadencierImportResponse> imports) {
}
