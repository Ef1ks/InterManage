package com.intermanage.backend.sales.dto;

import jakarta.validation.constraints.NotBlank;

public record SlowMovingExcludeRequest(@NotBlank(message = "Wskaż artykuł") String itm8) {
}
