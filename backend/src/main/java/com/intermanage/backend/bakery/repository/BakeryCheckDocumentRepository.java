package com.intermanage.backend.bakery.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.bakery.entity.BakeryCheckDocument;

public interface BakeryCheckDocumentRepository extends JpaRepository<BakeryCheckDocument, UUID> {
}
