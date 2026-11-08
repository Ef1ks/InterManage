package com.intermanage.backend.staff.dto;

import java.util.UUID;

/** Konto bez stanowiska, które można podpiąć jako pracownika tego sklepu. */
public record AccountOption(UUID id, String displayName, String username, String roleName, boolean owner) {
}
