package com.intermanage.backend.ksef.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.ksef.entity.KsefInvoiceXml;

public interface KsefInvoiceXmlRepository extends JpaRepository<KsefInvoiceXml, UUID> {

    interface Deposit {
        UUID getInvoiceId();

        BigDecimal getDepositNet();
    }

    /** Kaucje faktur z odczytanym XML (bez treści pliku), tylko niezerowe. */
    @Query("select x.invoiceId as invoiceId, x.depositNet as depositNet from KsefInvoiceXml x "
            + "where x.invoiceId in :ids and x.depositNet <> 0")
    List<Deposit> findDeposits(Collection<UUID> ids);
}
