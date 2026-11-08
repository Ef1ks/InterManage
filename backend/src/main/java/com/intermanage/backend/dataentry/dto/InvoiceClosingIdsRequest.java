package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record InvoiceClosingIdsRequest(@NotEmpty List<UUID> ids) {
}
