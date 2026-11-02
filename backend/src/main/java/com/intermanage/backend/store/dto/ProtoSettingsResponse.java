package com.intermanage.backend.store.dto;

import java.time.Instant;
import java.util.List;

import com.intermanage.backend.store.entity.ProtocolKind;

/**
 * Folder plików PROTO (zawsze podany: ustawiony albo domyślny), ukryte protokoły i folder pliku nowych artykułów
 * ({@code null} = bez podpowiedzi).
 */
public record ProtoSettingsResponse(String folderPath, boolean defaultFolder, List<ProtocolKind> hiddenProtocols,
        String productFileFolderPath, String updatedBy, Instant updatedAt) {
}
