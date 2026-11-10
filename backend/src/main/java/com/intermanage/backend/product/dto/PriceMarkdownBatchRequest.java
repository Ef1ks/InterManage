package com.intermanage.backend.product.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Kilka przecen naraz, np. wszystkie artykuły z makra ustawiania promocji. */
public record PriceMarkdownBatchRequest(@NotEmpty @Size(max = 1000) List<@Valid PriceMarkdownRequest> items) {
}
