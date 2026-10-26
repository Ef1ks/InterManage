package com.intermanage.backend.dto;

import com.intermanage.backend.entity.OrderUnit;

import jakarta.validation.constraints.NotNull;

public record UpdateOrderUnitRequest(@NotNull(message = "Wybierz jednostkę") OrderUnit orderUnit) {
}
