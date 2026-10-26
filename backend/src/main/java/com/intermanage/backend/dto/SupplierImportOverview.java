package com.intermanage.backend.dto;

import java.util.List;

/** Karta listy dostawców w Imporcie danych: ilu dostawców ma sklep (w tym dodanych ręcznie) i ostatnie wgrania. */
public record SupplierImportOverview(long supplierCount, long manualCount, List<SupplierImportResponse> imports) {
}
