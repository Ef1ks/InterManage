package com.intermanage.backend.document.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.document.entity.DocumentTransferReceivedLine;

public interface DocumentTransferReceivedLineRepository extends JpaRepository<DocumentTransferReceivedLine, UUID> {

    List<DocumentTransferReceivedLine> findByTargetId(UUID targetId);
}
