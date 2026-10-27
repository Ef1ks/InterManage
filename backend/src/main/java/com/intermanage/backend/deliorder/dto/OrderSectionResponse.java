package com.intermanage.backend.dto;

import java.util.List;
import java.util.UUID;

public record OrderSectionResponse(UUID id, String name, List<OrderCategoryResponse> categories) {
}
