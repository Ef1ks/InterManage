package com.intermanage.backend.nomenclature.dto;

/** Wynik wgrania pliku marż: ile pozycji się zmieniło i ile ma teraz ustawioną marżę. */
public record NomenclatureMarginImportResponse(int changedCount, int withMarginCount) {
}
