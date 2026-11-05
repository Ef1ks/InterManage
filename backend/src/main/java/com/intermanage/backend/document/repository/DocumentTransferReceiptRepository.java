package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentTransferReceipt;

/** Dokumenty sklepów docelowych przypięte do ruchów międzymagazynowych; liczą się nieodpięte przy aktywnym wskazaniu. */
public interface DocumentTransferReceiptRepository extends JpaRepository<DocumentTransferReceipt, UUID> {

    @Query("""
            select r from DocumentTransferReceipt r join fetch r.target t join fetch t.document d join fetch d.store
            where r.document.id in :documentIds and r.unlinkedAt is null and t.deletedAt is null""")
    List<DocumentTransferReceipt> findActiveByDocumentIds(Collection<UUID> documentIds);
}
