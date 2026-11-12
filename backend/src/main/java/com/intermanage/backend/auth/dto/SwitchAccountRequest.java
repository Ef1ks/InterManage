package com.intermanage.backend.auth.dto;

/** PIN albo hasło konta, na które się przełącza (zależnie od {@link SwitchAccountResponse#unlock()}). */
public record SwitchAccountRequest(String pin, String password) {
}
