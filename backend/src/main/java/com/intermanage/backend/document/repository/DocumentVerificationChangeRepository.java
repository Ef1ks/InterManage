package com.intermanage.backend.document.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.document.entity.DocumentVerificationChange;

public interface DocumentVerificationChangeRepository extends JpaRepository<DocumentVerificationChange, UUID> {
}
