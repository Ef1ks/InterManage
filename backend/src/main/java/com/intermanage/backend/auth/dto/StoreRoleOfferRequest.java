package com.intermanage.backend.auth.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record StoreRoleOfferRequest(@NotEmpty(message = "Wybierz sklep") List<UUID> storeIds) {
}
