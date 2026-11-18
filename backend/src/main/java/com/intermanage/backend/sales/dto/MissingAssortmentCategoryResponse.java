package com.intermanage.backend.sales.dto;

import java.util.UUID;

/**
 * Kategoria brakującego asortymentu widoczna w sklepie: własna ({@code own}) albo sklepu zaprzyjaźnionego
 * ({@code storeNumber}, {@code storeName}); zmieniać i usuwać może ją tylko sklep, który ją założył.
 * {@code articleCount}: ile artykułów bieżącego sklepu jest do niej przypisanych.
 */
public record MissingAssortmentCategoryResponse(UUID id, String name, UUID storeId, String storeNumber,
        String storeName, boolean own, long articleCount) {
}
