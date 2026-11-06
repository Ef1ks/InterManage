package com.intermanage.backend.bakery.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Dokument do przypięcia: bon, faktura albo skan do przywrócenia. */
public record BakeryLinkRequest(@NotNull UUID id) {
}
