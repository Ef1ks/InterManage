package com.intermanage.backend.task.entity;

/** Wpis w historii zadania; nazwa trafia do bazy. */
public enum TaskEventKind {
    CREATED,
    EDITED,
    ITEM_CHECKED,
    ITEM_UNCHECKED,
    DONE,
    UNDONE,
    ACCEPTED,
    RETURNED,
    DELETED,
    RESTORED,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED,
    ATTACHMENT_RESTORED
}
