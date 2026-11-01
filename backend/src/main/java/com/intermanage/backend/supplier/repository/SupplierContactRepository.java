package com.intermanage.backend.supplier.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.supplier.entity.SupplierContact;

public interface SupplierContactRepository extends JpaRepository<SupplierContact, UUID> {

    @Query("select c from SupplierContact c where c.profile.id = :profileId and c.deletedAt is null order by c.createdAt")
    List<SupplierContact> findActive(UUID profileId);

    /** Aktywne kontakty profili, do listy dostawców (przedstawiciel i liczba kontaktów). */
    @Query("select c from SupplierContact c where c.profile.id in :profileIds and c.deletedAt is null order by c.createdAt")
    List<SupplierContact> findActive(Collection<UUID> profileIds);

    @Query("select c from SupplierContact c where c.id = :id and c.profile.id = :profileId")
    Optional<SupplierContact> findOfProfile(UUID profileId, UUID id);
}
