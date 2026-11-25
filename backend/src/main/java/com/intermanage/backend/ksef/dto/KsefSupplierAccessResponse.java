package com.intermanage.backend.ksef.dto;

import java.util.UUID;

/**
 * Konto sklepu i to, czy w Fakturach KSeF widzi wszystkich dostawców. {@code reason}: {@code OWNER}, {@code ADERON},
 * {@code ACCOUNTING} (z urzędu, nie da się wyłączyć), {@code GRANTED} (włączone przez Aderona) albo {@code NONE}
 * (tylko dostawcy z listy dostawców).
 */
public record KsefSupplierAccessResponse(UUID userId, String name, String rankName, String reason, boolean allSuppliers) {
}
