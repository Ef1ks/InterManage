package com.intermanage.backend.document.entity;

/** Status weryfikacji przyjęcia PZ; nazwa stałej trafia do bazy. */
public enum DocumentVerificationStatus {
    /** W trakcie weryfikacji: czeka na kierownika. */
    IN_PROGRESS,
    /** Zweryfikowane. */
    VERIFIED
}
