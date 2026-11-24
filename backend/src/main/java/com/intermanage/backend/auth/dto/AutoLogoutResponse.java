package com.intermanage.backend.auth.dto;

/**
 * Automatyczne wylogowywanie konta. {@code minutes}: po ilu minutach bez ruchu (puste, gdy nie ustawiono, a aplikacja
 * nie ma domyślnego czasu), {@code workdayLock}: także o {@code workdayLockAt} (puste = aplikacja nie ma tej godziny).
 */
public record AutoLogoutResponse(boolean enabled, Integer minutes, boolean workdayLock, String workdayLockAt) {
}
