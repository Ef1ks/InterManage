package com.intermanage.backend.ksef.dto;

import jakarta.validation.constraints.NotBlank;

/** Podłączenie sklepu do firmy połączonej z KSeF w innym sklepie. */
public record KsefLinkConnectionRequest(@NotBlank String nip) {
}
