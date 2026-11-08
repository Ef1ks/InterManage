package com.intermanage.backend.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank(message = "Podaj nazwę działu")
        @Size(max = 80, message = "Maksymalnie 80 znaków") String name) {
}
