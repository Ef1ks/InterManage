package com.intermanage.backend.deliorder.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliorder.entity.DeliOrder;
import com.intermanage.backend.deliorder.entity.DeliOrderStatus;

public interface DeliOrderRepository extends JpaRepository<DeliOrder, UUID> {

    /**
     * Zamówienia, które przyszły do sklepu-dostawcy, bez usuniętych przez niego i bez połączonych w inne: najpierw
     * nowe, potem odczytane do realizacji, potem od najświeższych. Wycofane przez zamawiającego zostają na liście ze statusem "usunięte".
     */
    @EntityGraph(attributePaths = {"formStore", "store", "submittedBy"})
    @Query("""
            select o from DeliOrder o where o.formStore.id = :formStoreId and o.formStoreDeletedAt is null
              and o.mergedInto is null and o.splitAt is null
            order by case when o.storeDeletedAt is not null then 2
                          when o.status = com.intermanage.backend.deliorder.entity.DeliOrderStatus.NEW then 0
                          when o.status = com.intermanage.backend.deliorder.entity.DeliOrderStatus.READ then 1
                          else 2 end,
                     o.submittedAt desc""")
    List<DeliOrder> findIncoming(UUID formStoreId, Limit limit);

    /** Zamówienia wysłane przez sklep, bez usuniętych przez niego, od najświeższych. */
    @EntityGraph(attributePaths = {"formStore", "store", "submittedBy"})
    @Query("""
            select o from DeliOrder o where o.store.id = :storeId and o.storeDeletedAt is null
              and o.mergedInto is null and o.splitAt is null
            order by o.submittedAt desc""")
    List<DeliOrder> findSent(UUID storeId, Limit limit);

    /** Nowe, jeszcze nieodczytane zamówienia (licznik w menu): nieusunięte po żadnej stronie i niepołączone. */
    @Query("""
            select count(o) from DeliOrder o where o.formStore.id = :formStoreId and o.status = :status
              and o.storeDeletedAt is null and o.formStoreDeletedAt is null
              and o.mergedInto is null and o.splitAt is null""")
    long countActive(UUID formStoreId, DeliOrderStatus status);
}
