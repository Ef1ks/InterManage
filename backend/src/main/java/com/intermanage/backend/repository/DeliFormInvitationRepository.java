package com.intermanage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.DeliFormInvitation;
import com.intermanage.backend.entity.InvitationStatus;

public interface DeliFormInvitationRepository extends JpaRepository<DeliFormInvitation, UUID> {

    /** Zaproszenia wysłane z formularza sklepu-dostawcy. */
    @EntityGraph(attributePaths = {"formStore", "store"})
    @Query("select i from DeliFormInvitation i where i.formStore.id = :formStoreId order by i.store.number")
    List<DeliFormInvitation> findSent(UUID formStoreId);

    /** Zaproszenia otrzymane przez sklep w danym statusie. */
    @EntityGraph(attributePaths = {"formStore", "store"})
    @Query("""
            select i from DeliFormInvitation i
            where i.store.id = :storeId and i.status = :status
            order by i.formStore.number""")
    List<DeliFormInvitation> findReceived(UUID storeId, InvitationStatus status);

    Optional<DeliFormInvitation> findByFormStoreIdAndStoreId(UUID formStoreId, UUID storeId);

    boolean existsByFormStoreIdAndStoreIdAndStatus(UUID formStoreId, UUID storeId, InvitationStatus status);

    Optional<DeliFormInvitation> findByIdAndFormStoreId(UUID id, UUID formStoreId);

    Optional<DeliFormInvitation> findByIdAndStoreId(UUID id, UUID storeId);

    long countByStoreIdAndStatus(UUID storeId, InvitationStatus status);
}
