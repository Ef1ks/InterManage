package com.intermanage.backend.pricecheck.entity;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Artykuł z bieżącej listy cadencier sklepu (V38), tylko do odczytu: listę zapisuje jednym zapytaniem
 * {@code CadencierImportRepository.merge}. Encja służy podzapytaniom w wyszukiwarkach kartoteki.
 */
@Entity
@Immutable
@Table(name = "cadencier_items")
public class CadencierItem {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 13)
    private String itm8;

    protected CadencierItem() {
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getItm8() {
        return itm8;
    }
}
