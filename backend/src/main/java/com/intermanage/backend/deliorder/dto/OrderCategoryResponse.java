package com.intermanage.backend.deliorder.dto;

import java.util.List;
import java.util.UUID;

public record OrderCategoryResponse(UUID id, String name, List<OrderProductResponse> products) {
}
