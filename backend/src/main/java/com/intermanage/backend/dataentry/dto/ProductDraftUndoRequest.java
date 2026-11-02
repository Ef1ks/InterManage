package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** „Cofnij” potwierdzenia pliku CADBEZP: szkice z {@link ProductDraftResult#createdIds()}. */
public record ProductDraftUndoRequest(@NotEmpty @Size(max = 2000) List<UUID> ids) {
}
