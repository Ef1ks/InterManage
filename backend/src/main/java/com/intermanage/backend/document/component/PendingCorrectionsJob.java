package com.intermanage.backend.document.component;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.document.service.DocumentKsefLinkService;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;

/**
 * W tle przypina korekty od dostawcy z fakturą korygującą do przyjęć z fakturą korygowaną
 * ({@link DocumentKsefLinkService#attachPendingCorrections}): korekty przypięte, zanim to działało, i takie, których XML
 * odczytano po przypięciu ({@code KsefCorrectedInvoiceService}). Rusza po odczycie faktur korygowanych, w tym samym
 * odstępie.
 */
@Component
public class PendingCorrectionsJob {

    private static final Logger log = LoggerFactory.getLogger(PendingCorrectionsJob.class);

    private final KsefConnectionRepository connections;
    private final DocumentKsefLinkService ksefLinks;
    private final boolean scheduled;

    public PendingCorrectionsJob(KsefConnectionRepository connections, DocumentKsefLinkService ksefLinks,
            @Value("${app.ksef.sync-enabled}") boolean scheduled) {
        this.connections = connections;
        this.ksefLinks = ksefLinks;
        this.scheduled = scheduled;
    }

    @Scheduled(fixedDelayString = "${app.ksef.corrections-interval}", initialDelayString = "PT4M")
    public void attachAll() {
        if (!scheduled) {
            return;
        }
        List<UUID> storeIds = connections.findAll().stream().map(KsefConnection::getStoreId).toList();
        for (UUID storeId : storeIds) {
            try {
                int attached = ksefLinks.attachPendingCorrections(storeId, null).size();
                if (attached > 0) {
                    log.info("Sklep {}: {} korekt od dostawcy przypiętych do przyjęć z fakturą korygowaną",
                            storeId, attached);
                }
            } catch (RuntimeException e) {
                log.warn("Przypinanie korekt do przyjęć w sklepie {} nie powiodło się: {}", storeId, e.getMessage());
            }
        }
    }
}
