package com.intermanage.backend.auth.entity;

/**
 * Poziom dostępu do kategorii. Brak wpisu w roli oznacza brak dostępu.
 * EDIT obejmuje VIEW.
 */
public enum PermissionLevel {

    VIEW,
    EDIT;

    public boolean includes(PermissionLevel other) {
        return this.ordinal() >= other.ordinal();
    }

    /** Nazwa uprawnienia Spring Security, np. {@code SALES:VIEW}. */
    public static String authority(PermissionCategory category, PermissionLevel level) {
        return category.name() + ":" + level.name();
    }
}
