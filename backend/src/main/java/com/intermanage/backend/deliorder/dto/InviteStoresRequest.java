package com.intermanage.backend.deliorder.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record InviteStoresRequest(@NotEmpty(message = "Wybierz co najmniej jeden sklep") List<UUID> storeIds) {
}
