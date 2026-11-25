package com.intermanage.backend.collector.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Listy, z których powstaje protokół; {@code fileName} przy potwierdzeniu zapisu pliku. */
public record CollectorProtocolRequest(
        @NotEmpty(message = "Wybierz listę") @Size(max = 50, message = "Najwyżej 50 list naraz") List<UUID> ids,
        @Size(max = 200) String fileName) {
}
