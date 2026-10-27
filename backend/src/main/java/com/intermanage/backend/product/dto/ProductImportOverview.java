package com.intermanage.backend.product.dto;

import java.util.List;

public record ProductImportOverview(long productCount, List<ProductImportResponse> imports) {
}
