package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

public record DepartmentResponse(UUID id, String name, List<PositionResponse> positions) {
}
