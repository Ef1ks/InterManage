package com.intermanage.backend.ksef.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.ksef.entity.KsefInvoiceXml;

public interface KsefInvoiceXmlRepository extends JpaRepository<KsefInvoiceXml, UUID> {
}
