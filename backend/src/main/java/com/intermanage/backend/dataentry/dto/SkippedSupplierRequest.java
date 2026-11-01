package com.intermanage.backend.dataentry.dto;

import com.intermanage.backend.dataentry.entity.SupplierRuleMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code mode}: HIDDEN pomijany (domyślnie), DONE odkładany do wczytanych. */
public record SkippedSupplierRequest(@NotBlank @Size(max = 20) String sellerNip, @Size(max = 500) String sellerName,
        SupplierRuleMode mode) {
}
