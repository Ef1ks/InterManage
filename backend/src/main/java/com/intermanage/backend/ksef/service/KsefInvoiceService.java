package com.intermanage.backend.ksef.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.repository.DocumentKsefInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefInvoicePdf;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.component.KsefRateLimiter;
import com.intermanage.backend.ksef.dto.KsefInvoicePage;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.dto.KsefOverview;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInternalId;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceEntry;
import com.intermanage.backend.ksef.entity.KsefInvoiceXml;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;
import com.intermanage.backend.ksef.entity.KsefSyncState;
import com.intermanage.backend.ksef.mapper.KsefMapper;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInternalIdRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceEntryRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.ksef.repository.KsefInvoiceXmlRepository;
import com.intermanage.backend.ksef.repository.KsefSkippedSupplierRepository;
import com.intermanage.backend.ksef.repository.KsefSyncStateRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Lista faktur KSeF sklepu: z faktur firmy (NIP połączenia sklepu, wspólnych dla jej sklepów) tylko te z IDWew sklepu
 * i bez IDWew, filtry i sumy liczone w bazie ({@link KsefInvoiceSearch}), stronami po {@value #PAGE_SIZE}.
 * "Wprowadzona" wynika z przyjęć PZ sklepu albo z ręcznego oznaczenia w sklepie, które da się cofnąć.
 */
@Service
public class KsefInvoiceService {

    public static final int PAGE_SIZE = 100;

    private final KsefInvoiceRepository invoices;
    private final KsefConnectionRepository connections;
    private final KsefInternalIdRepository internalIds;
    private final KsefSkippedSupplierRepository skippedSuppliers;
    private final DocumentRepository documents;
    private final AppUserRepository users;
    private final KsefSessionService sessions;
    private final KsefClient client;
    private final KsefMapper mapper;
    private final KsefInvoicePdf invoicePdf;
    private final KsefInvoiceXmlRepository xmlFiles;
    private final KsefRateLimiter limiter;
    private final KsefSyncService syncService;
    private final KsefSyncStateRepository syncStates;
    private final KsefInvoiceEntryRepository entries;
    private final DocumentKsefInvoiceRepository documentLinks;
    private final StoreRepository stores;

    public KsefInvoiceService(KsefInvoiceRepository invoices, KsefConnectionRepository connections,
            KsefInternalIdRepository internalIds, KsefSkippedSupplierRepository skippedSuppliers,
            DocumentRepository documents, AppUserRepository users, KsefSessionService sessions, KsefClient client,
            KsefMapper mapper, KsefInvoicePdf invoicePdf, KsefInvoiceXmlRepository xmlFiles,
            KsefRateLimiter limiter, KsefSyncService syncService, KsefSyncStateRepository syncStates,
            KsefInvoiceEntryRepository entries, DocumentKsefInvoiceRepository documentLinks, StoreRepository stores) {
        this.documentLinks = documentLinks;
        this.stores = stores;
        this.invoices = invoices;
        this.connections = connections;
        this.internalIds = internalIds;
        this.skippedSuppliers = skippedSuppliers;
        this.documents = documents;
        this.users = users;
        this.sessions = sessions;
        this.client = client;
        this.mapper = mapper;
        this.invoicePdf = invoicePdf;
        this.xmlFiles = xmlFiles;
        this.limiter = limiter;
        this.syncService = syncService;
        this.syncStates = syncStates;
        this.entries = entries;
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    @Transactional(readOnly = true)
    public KsefOverview overview(UUID storeId) {
        KsefConnection connection = connections.findById(storeId).orElse(null);
        String nip = connection != null ? connection.getNip() : null;
        KsefSyncState state = nip != null ? syncStates.findById(nip).orElse(null) : null;
        KsefSyncService.Progress progress = nip != null ? syncService.progress(nip) : null;
        return new KsefOverview(connection != null, nip,
                state != null ? state.getSyncedAt() : null,
                state != null ? state.getSyncOk() : null,
                state != null ? state.getSyncMessage() : null,
                progress != null, progress != null ? progress.startedAt() : null,
                progress != null ? progress.created() : 0,
                nip != null ? invoices.countByNip(nip) : 0,
                internalIds.findActive(storeId).stream().map(mapper::toResponse).toList());
    }

    @Transactional(readOnly = true)
    public KsefInvoicePage list(UUID storeId, int page, KsefInvoiceSearch.Filter filter, String sort,
            String direction) {
        String nip = connections.findById(storeId).map(KsefConnection::getNip).orElse(null);
        if (nip == null) {
            return new KsefInvoicePage(List.of(), 0, false, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Specification<KsefInvoice> matching = KsefInvoiceSearch.matching(storeId, nip, ownInternalIds(storeId),
                filter);
        Page<KsefInvoice> found = invoices.findAll(matching,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, KsefInvoiceSearch.sort(sort, direction)));
        KsefInvoiceRepository.Totals totals = invoices.totals(matching);
        return new KsefInvoicePage(summaries(storeId, found.getContent()), found.getNumber(), found.hasNext(),
                totals.count(), totals.netAmount(), totals.grossAmount());
    }

    /** Ręczne oznaczenie "wprowadzona" w sklepie (np. numer w PZ zapisany inaczej) albo jego cofnięcie. */
    @Transactional
    public KsefInvoiceSummary setEntered(UUID storeId, UUID id, boolean entered, UUID userId) {
        KsefInvoice invoice = visible(storeId, id);
        KsefInvoiceEntry.Key key = new KsefInvoiceEntry.Key(storeId, invoice.getId());
        boolean marked = entries.existsById(key);
        if (entered && !marked) {
            AppUser user = userId != null ? users.getReferenceById(userId) : null;
            entries.save(new KsefInvoiceEntry(storeId, invoice.getId(), user));
        } else if (!entered && marked) {
            entries.deleteById(key);
        }
        entries.flush();
        return summaries(storeId, List.of(invoice)).getFirst();
    }

    /** Oryginał faktury w XML (z pozycjami), z KSeF przy pierwszym pobraniu, potem z bazy. */
    @Transactional
    public ExportFile xml(UUID storeId, UUID id) {
        KsefInvoice invoice = visible(storeId, id);
        return new ExportFile(invoice.getKsefNumber() + ".xml", "application/xml", download(storeId, invoice));
    }

    /**
     * Faktura do czytania i wydruku: PDF zrobiony z XML. Pobrany XML zostaje zapisany także wtedy, gdy PDF się nie
     * uda (błąd odczytu to {@link ApiException}).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public ExportFile pdf(UUID storeId, UUID id) {
        KsefInvoice invoice = visible(storeId, id);
        byte[] content;
        try {
            content = invoicePdf.pdf(download(storeId, invoice), invoice.getKsefNumber(), invoice.getSellerNip(),
                    invoice.getInvoicingDate());
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            throw ApiException.badRequest("Nie udało się odczytać faktury z KSeF: " + e.getMessage());
        }
        return new ExportFile(fileName(invoice) + ".pdf", "application/pdf", content);
    }

    /**
     * XML faktury: z bazy, a za pierwszym razem z KSeF (w granicach limitu pobrań, {@link KsefRateLimiter}) i od razu
     * zapisany - faktura w KSeF się nie zmienia.
     */
    private byte[] download(UUID storeId, KsefInvoice invoice) {
        return xmlFiles.findById(invoice.getId()).map(KsefInvoiceXml::getContent).orElseGet(() -> {
            KsefConnection connection = connections.findById(storeId)
                    .orElseThrow(() -> ApiException.notFound("Sklep nie ma połączenia z KSeF"));
            byte[] content;
            try {
                String token = sessions.accessToken(connection);
                content = limiter.call(connection.getNip(), KsefRateLimiter.Operation.INVOICE,
                        () -> client.invoiceXml(token, invoice.getKsefNumber()));
            } catch (KsefClient.KsefException e) {
                throw e.rateLimited() ? new ApiException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage())
                        : ApiException.badRequest("KSeF: " + e.getMessage());
            }
            xmlFiles.save(new KsefInvoiceXml(invoice.getId(), content));
            return content;
        });
    }

    /** "Faktura FV_12_2026 MLECZARNIA" - znaki niedozwolone w nazwach plików zamienione na _. */
    private static String fileName(KsefInvoice invoice) {
        String name = "Faktura " + invoice.getInvoiceNumber()
                + (invoice.getSellerName() != null ? " " + invoice.getSellerName() : "");
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").strip();
        return name.length() > 120 ? name.substring(0, 120).strip() : name;
    }

    private KsefInvoice visible(UUID storeId, UUID id) {
        String nip = connections.findById(storeId).map(KsefConnection::getNip)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono faktury"));
        KsefInvoice invoice = invoices.findByIdAndNip(id, nip)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono faktury"));
        if (!KsefInvoiceSearch.visibleInStore(invoice, ownInternalIds(storeId))) {
            throw ApiException.notFound("Nie znaleziono faktury");
        }
        return invoice;
    }

    private List<KsefInvoiceSummary> summaries(UUID storeId, List<KsefInvoice> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        // Faktury są wspólne dla firmy: dokumenty i oznaczenia z każdego jej sklepu (najpierw z bieżącego).
        List<UUID> companyStores = companyStores(storeId);
        List<UUID> ids = page.stream().map(KsefInvoice::getId).toList();
        Map<String, List<Document>> deliveries = documents.findCompanyDeliveriesBySupplierDocument(companyStores,
                        page.stream().map(invoice -> normalized(invoice.getInvoiceNumber())).distinct().toList())
                .stream().collect(Collectors.groupingBy(document -> normalized(document.getSupplierDocumentNumber())));
        Set<String> skipped = skippedSuppliers.findActive(storeId).stream().map(KsefSkippedSupplier::getNip)
                .collect(Collectors.toSet());
        Map<UUID, KsefInvoiceEntry> marked = new HashMap<>();
        for (KsefInvoiceEntry entry : entries.findInStores(companyStores, ids)) {
            marked.merge(entry.getId().invoiceId(), entry,
                    (first, second) -> second.getId().storeId().equals(storeId) ? second : first);
        }
        Map<UUID, List<Document>> linked = documentLinks.findActiveByInvoiceIdsInStores(companyStores, ids)
                .stream().collect(Collectors.groupingBy(link -> link.getInvoice().getId(),
                        Collectors.mapping(DocumentKsefInvoice::getDocument, Collectors.toList())));
        Map<UUID, String> storeNames = new HashMap<>();
        stores.findAllById(companyStores).forEach(store -> storeNames.put(store.getId(), storeLabel(store)));
        return page.stream()
                .map(invoice -> {
                    KsefInvoiceEntry entry = marked.get(invoice.getId());
                    return mapper.toSummary(invoice, entry,
                            documentsOf(storeId, deliveries.getOrDefault(normalized(invoice.getInvoiceNumber()),
                                    List.of()), linked.getOrDefault(invoice.getId(), List.of())),
                            skipped.contains(invoice.getSellerNip()),
                            entry != null ? storeNames.get(entry.getId().storeId()) : null);
                })
                .toList();
    }

    /** Sklepy firmy (z połączeniem z tym samym NIP-em), zawsze z bieżącym. */
    private List<UUID> companyStores(UUID storeId) {
        List<UUID> result = new ArrayList<>(List.of(storeId));
        connections.findById(storeId).ifPresent(connection -> connections.findStoreIdsByNip(connection.getNip())
                .stream().filter(id -> !id.equals(storeId)).forEach(result::add));
        return result;
    }

    private static String storeLabel(Store store) {
        return store.getNumber() + " · " + store.getName();
    }

    /**
     * Przyjęcia z numerem faktury, potem dokumenty przypięte ręcznie (bez powtórzeń); dokumenty bieżącego sklepu przed
     * dokumentami innych sklepów firmy.
     */
    private static List<KsefInvoiceSummary.Delivery> documentsOf(UUID storeId, List<Document> byNumber,
            List<Document> linked) {
        List<KsefInvoiceSummary.Delivery> result = new ArrayList<>();
        for (Document document : byNumber) {
            result.add(delivery(document, false));
        }
        for (Document document : linked) {
            if (result.stream().noneMatch(delivery -> delivery.id().equals(document.getId()))) {
                result.add(delivery(document, true));
            }
        }
        result.sort(Comparator.comparing(delivery -> !delivery.storeId().equals(storeId)));
        return result;
    }

    private static KsefInvoiceSummary.Delivery delivery(Document document, boolean linked) {
        return new KsefInvoiceSummary.Delivery(document.getId(), document.getType().name(),
                document.getType().singular(), document.getNumber(), linked, document.getStore().getId(),
                storeLabel(document.getStore()));
    }

    /**
     * Przyjęcie PZ sklepu firmy z tą fakturą (z jej numerem albo przypięte) - do pobrania bonu dostawy z innego sklepu
     * tej samej firmy. Zwraca id sklepu przyjęcia.
     */
    @Transactional(readOnly = true)
    public UUID companyDeliveryStore(UUID storeId, UUID invoiceId, UUID documentId) {
        KsefInvoice invoice = visible(storeId, invoiceId);
        return summaries(storeId, List.of(invoice)).getFirst().deliveries().stream()
                .filter(delivery -> delivery.id().equals(documentId) && delivery.type().equals("PZ"))
                .map(KsefInvoiceSummary.Delivery::storeId)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przyjęcia tej faktury"));
    }

    /** Jedna faktura po zmianie (np. przypięciu dokumentu) - w formie wiersza listy. */
    @Transactional(readOnly = true)
    public KsefInvoiceSummary summary(UUID storeId, UUID id) {
        return summaries(storeId, List.of(visible(storeId, id))).getFirst();
    }

    private Set<String> ownInternalIds(UUID storeId) {
        return internalIds.findActive(storeId).stream().map(KsefInternalId::getInternalId)
                .collect(Collectors.toSet());
    }

    private static String normalized(String number) {
        return number.toUpperCase(Locale.ROOT);
    }
}
