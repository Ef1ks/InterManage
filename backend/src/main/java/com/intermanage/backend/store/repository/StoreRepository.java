package com.intermanage.backend.store.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.store.entity.Store;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    @Query("select s from Store s order by s.number")
    List<Store> findAllOrdered();

    @Query("select s from Store s where s.active = true order by s.number")
    List<Store> findActiveOrdered();

    boolean existsByNumber(String number);

    boolean existsByNumberAndIdNot(String number, UUID id);

    @Query("select s.id, count(u) from AppUser u join u.stores s group by s.id")
    List<Object[]> countUsersPerStore();

    /** Czy pracownik ma przypisany ten sklep i sklep jest aktywny. */
    @Query("""
            select count(s) > 0 from AppUser u join u.stores s
            where u.id = :userId and s.id = :storeId and s.active = true""")
    boolean isAssigned(UUID userId, UUID storeId);
}
