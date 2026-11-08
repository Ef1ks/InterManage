package com.intermanage.backend.staff.dto;

import java.util.UUID;

/** Dział do wyboru w grafiku. {@code canEdit}: użytkownik układa jego grafik. */
public record ScheduleDepartment(UUID id, String name, boolean canEdit) {
}
