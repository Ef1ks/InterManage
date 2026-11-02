package com.intermanage.backend.meat.dto;

import java.util.UUID;

/** Dostawca mięsa z listy dostawców sklepu ({@code code} pusty u dodanego ręcznie). */
public record MeatSupplier(UUID id, String code, String name, String nip) {
}
