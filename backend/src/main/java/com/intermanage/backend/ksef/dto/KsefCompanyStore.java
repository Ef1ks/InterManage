package com.intermanage.backend.ksef.dto;

import java.util.UUID;

/** Inny sklep użytkownika z NIP-em tej samej firmy; {@code connected} = ma już jej połączenie z KSeF. */
public record KsefCompanyStore(UUID id, String number, String name, boolean connected) {
}
