package com.intermanage.backend.staff.dto;

import java.util.UUID;

/** Rola do wyboru przy stanowisku; {@code grantable}: wybierający sam ma wszystkie jej uprawnienia. */
public record RoleOption(UUID id, String name, boolean grantable) {
}
