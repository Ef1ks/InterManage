package com.intermanage.backend.supplierreturn.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.supplierreturn.entity.SupplierReturnEvent;

public interface SupplierReturnEventRepository extends JpaRepository<SupplierReturnEvent, UUID> {
}
