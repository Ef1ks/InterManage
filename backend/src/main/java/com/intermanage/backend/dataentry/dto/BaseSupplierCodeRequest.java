package com.intermanage.backend.dataentry.dto;

import java.util.UUID;

import jakarta.validation.constraints.Size;

/** Przypisanie kodu dostawcy z bazy: dostawca z listy dostawców sklepu ({@code supplierId}) albo sama nazwa. */
public record BaseSupplierCodeRequest(UUID supplierId, @Size(max = 500) String name) {
}
