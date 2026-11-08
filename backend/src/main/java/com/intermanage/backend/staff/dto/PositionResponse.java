package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

public record PositionResponse(UUID id, UUID departmentId, String name, UUID roleId, String roleName,
        List<ShiftResponse> shifts, long employeeCount) {
}
