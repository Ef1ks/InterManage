package com.intermanage.backend.store.dto;

import java.util.List;

import com.intermanage.backend.store.entity.ProtocolKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Folder plików PROTO (pusty = domyślny), protokoły ukryte w oknie generowania i folder pliku nowych artykułów. */
public record ProtoSettingsRequest(
        @Size(max = 300, message = "Ścieżka może mieć najwyżej 300 znaków") String folderPath,
        @NotNull(message = "Podaj ukryte protokoły") List<@NotNull ProtocolKind> hiddenProtocols,
        @Size(max = 300, message = "Ścieżka może mieć najwyżej 300 znaków") String productFileFolderPath) {
}
