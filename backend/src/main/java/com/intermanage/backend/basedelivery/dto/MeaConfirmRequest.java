package com.intermanage.backend.basedelivery.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Kody MEA wpisane w Mercalys. */
public record MeaConfirmRequest(
        @NotNull @Size(min = 1, max = 2000, message = "Zaznacz kody do potwierdzenia")
        List<@NotBlank @Size(max = 20) String> codes) {
}
