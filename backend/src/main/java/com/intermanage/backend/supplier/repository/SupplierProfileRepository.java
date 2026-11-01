package com.intermanage.backend.supplier.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.supplier.entity.SupplierProfile;

public interface SupplierProfileRepository extends JpaRepository<SupplierProfile, UUID> {

    /** Profile dostawców firmy (NIP sklepu); do listy dostawców sklepu. */
    @Query("select p from SupplierProfile p where p.companyKey = :companyKey")
    List<SupplierProfile> findByCompany(String companyKey);

    @Query("select p from SupplierProfile p where p.companyKey = :companyKey and p.supplierKey = :supplierKey")
    Optional<SupplierProfile> findByKeys(String companyKey, String supplierKey);
}
