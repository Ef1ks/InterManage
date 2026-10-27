package com.intermanage.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.entity.KsefInvoiceXml;

public interface KsefInvoiceXmlRepository extends JpaRepository<KsefInvoiceXml, UUID> {
}
