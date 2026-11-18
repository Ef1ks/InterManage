package com.intermanage.backend.product.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.product.repository.ArticleMergeRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Pierwsze wypełnienie połączeń artykułów (V165 tworzy pustą tabelę): po starcie, w tle, gdy tabela jest pusta,
 * każdy sklep we własnej transakcji (ok. 2 s na sklep). Dalej połączenia odświeżają importy kartoteki i multikodów.
 */
@Component
public class ArticleMergeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ArticleMergeBootstrap.class);

    private final ArticleMergeRepository merges;
    private final StoreRepository stores;
    private final TransactionTemplate transaction;

    public ArticleMergeBootstrap(ArticleMergeRepository merges, StoreRepository stores,
            TransactionTemplate transaction) {
        this.merges = merges;
        this.stores = stores;
        this.transaction = transaction;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fillOnStart() {
        if (merges.count() > 0) {
            return;
        }
        Thread.ofVirtual().name("article-merges-bootstrap").start(() -> {
            for (Store store : stores.findAllOrdered()) {
                try {
                    transaction.executeWithoutResult(status -> merges.refresh(store.getId()));
                } catch (RuntimeException e) {
                    log.warn("Nie udało się połączyć artykułów sklepu {}", store.getNumber(), e);
                }
            }
            log.info("Połączenia artykułów przez multikody: {}", merges.count());
        });
    }
}
