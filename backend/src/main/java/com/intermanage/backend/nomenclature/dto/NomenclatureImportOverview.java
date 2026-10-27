package com.intermanage.backend.nomenclature.dto;

import java.util.List;

/** Karta nomenklatury w Imporcie danych: ile pozycji ma sklep (rejony, rodziny, podrodziny) i ostatnie wgrania. */
public record NomenclatureImportOverview(long regionCount, long familyCount, long subfamilyCount,
        List<NomenclatureImportResponse> imports) {
}
