package com.intermanage.backend.product.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.DepositKind;

public interface DepositKindRepository extends JpaRepository<DepositKind, UUID> {

    @Query("select k from DepositKind k where k.store.id = :storeId order by k.code")
    List<DepositKind> findAllByStore(UUID storeId);
}
