package com.intermanage.backend.deliprice.dto;

import java.util.UUID;

/** Sklep zaprzyjaźniony: zakładka u góry kalkulatora. */
public record PartnerStore(UUID id, String number, String name) {
}
