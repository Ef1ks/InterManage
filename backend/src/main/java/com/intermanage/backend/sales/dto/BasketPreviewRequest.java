package com.intermanage.backend.sales.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Artykuły, które mają trafić do koszyka zakupowego (ITM8 z kartoteki sklepu). */
public record BasketPreviewRequest(
        @NotNull @Size(min = 1, max = 5000, message = "Od 1 do 5000 artykułów")
        List<@NotBlank @Size(max = 13) String> itm8s) {
}
