package com.intermanage.backend.chat.entity;

/** Rodzaj rozmowy; nazwa stałej trafia do bazy. */
public enum ChatKind {
    /** Czat całego sklepu: członkami są wszyscy z dostępem do sklepu, jeden na sklep. */
    STORE,
    /** Grupa w sklepie albo niezależna od sklepu, z członkami wybranymi przez zarządzającego. */
    GROUP,
    /** Rozmowa dwóch osób, jedna na parę, bez sklepu. */
    DIRECT
}
