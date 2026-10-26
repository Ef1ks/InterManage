package com.intermanage.backend.dto;

import java.util.UUID;

/**
 * Inny sklep użytkownika w ustawieniach KSeF połączonego sklepu. {@code nip}: firma, z którą sklep jest już połączony
 * ({@code null} = bez połączenia, można go podłączyć do tej firmy).
 */
public record KsefCompanyStore(UUID id, String number, String name, String nip) {
}
