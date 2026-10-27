package com.intermanage.backend.deliorder.dto;

import com.intermanage.backend.deliorder.entity.OrderUnit;

import jakarta.validation.constraints.NotNull;

public record UpdateOrderUnitRequest(@NotNull(message = "Wybierz jednostkę") OrderUnit orderUnit) {
}
