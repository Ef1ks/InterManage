package com.intermanage.backend.product.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record PriceMarkdownIdsRequest(@NotEmpty List<UUID> ids) {
}
