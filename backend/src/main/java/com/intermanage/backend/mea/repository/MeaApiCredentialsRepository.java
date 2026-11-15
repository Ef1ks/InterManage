package com.intermanage.backend.mea.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.mea.entity.MeaApiCredentials;

public interface MeaApiCredentialsRepository extends JpaRepository<MeaApiCredentials, UUID> {

    Optional<MeaApiCredentials> findByStoreId(UUID storeId);
}
