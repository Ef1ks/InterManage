package com.intermanage.backend.dto;

import java.util.List;

/** Firma (NIP) połączona z KSeF w innych sklepach, z ich nazwami. */
public record KsefCompanyResponse(String nip, List<String> stores) {
}
