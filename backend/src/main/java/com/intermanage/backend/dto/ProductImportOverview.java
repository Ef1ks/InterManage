package com.intermanage.backend.dto;

import java.util.List;

public record ProductImportOverview(long productCount, List<ProductImportResponse> imports) {
}
