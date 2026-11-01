package com.intermanage.backend.store.dto;

import java.time.Instant;
import java.util.List;

import com.intermanage.backend.store.entity.ProtocolKind;

/** Folder plików PROTO (zawsze podany: ustawiony albo domyślny) i ukryte protokoły. */
public record ProtoSettingsResponse(String folderPath, boolean defaultFolder, List<ProtocolKind> hiddenProtocols,
        String updatedBy, Instant updatedAt) {
}
