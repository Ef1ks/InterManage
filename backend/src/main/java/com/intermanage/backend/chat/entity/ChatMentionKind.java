package com.intermanage.backend.chat.entity;

/** Rodzaj oznaczenia w wiadomości; nazwa stałej trafia do bazy. */
public enum ChatMentionKind {
    /** {@code @osoba}: jeden członek rozmowy. */
    USER,
    /** {@code @wszyscy}: wszyscy członkowie rozmowy. */
    ALL,
    /** {@code @dział}: członkowie rozmowy z tego działu (stałe stanowisko w Pracownikach). */
    DEPARTMENT
}
