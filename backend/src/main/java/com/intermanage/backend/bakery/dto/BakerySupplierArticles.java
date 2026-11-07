package com.intermanage.backend.bakery.dto;

import java.util.List;
import java.util.UUID;

/** Artykuły dostawcy po nazwie; {@code hiddenProductIds}: odznaczone, czyli domyślnie poza listą WZ. */
public record BakerySupplierArticles(List<BakeryArticle> articles, List<UUID> hiddenProductIds) {
}
