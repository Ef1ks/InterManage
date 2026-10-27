package com.intermanage.backend.ksef.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.security.StoreAccess;
import com.intermanage.backend.common.component.Nip;
import com.intermanage.backend.common.component.SecretCipher;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefCredentials;
import com.intermanage.backend.ksef.dto.KsefCompanyResponse;
import com.intermanage.backend.ksef.dto.KsefCompanyStore;
import com.intermanage.backend.ksef.dto.KsefSeenInternalId;
import com.intermanage.backend.ksef.dto.KsefSettingsResponse;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInternalId;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;
import com.intermanage.backend.ksef.mapper.KsefMapper;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInternalIdRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefSkippedSupplierRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ustawienia KSeF sklepu: połączenie (NIP firmy, certyfikat KSeF z kluczem i hasłem, test logowania), IDWew sklepu
 * i stali dostawcy pomijani na liście. Faktury są wspólne dla firmy, więc sklep z połączeniem firmy już połączonej
 * w innym sklepie od razu je widzi. Kto ma dostęp do obu sklepów, może go podłączyć bez wgrywania certyfikatu
 * ({@link #linkConnection}); firm ze sklepów, do których nie ma dostępu, nie widzi. Usuwanie IDWew i dostawców to
 * znacznik, który da się cofnąć.
 */
@Service
public class KsefConnectionService {


    private final TransactionTemplate transaction;
    private final KsefConnectionRepository connections;
    private final KsefInternalIdRepository internalIds;
    private final KsefSkippedSupplierRepository skippedSuppliers;
    private final KsefInvoiceRepository invoices;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final KsefCredentials credentials;
    private final SecretCipher cipher;
    private final KsefSessionService sessions;
    private final KsefMapper mapper;
    private final StoreAccess storeAccess;

    public KsefConnectionService(TransactionTemplate transaction, KsefConnectionRepository connections,
            KsefInternalIdRepository internalIds, KsefSkippedSupplierRepository skippedSuppliers,
            KsefInvoiceRepository invoices, StoreRepository stores, AppUserRepository users,
            KsefCredentials credentials, SecretCipher cipher, KsefSessionService sessions, KsefMapper mapper,
            StoreAccess storeAccess) {
        this.transaction = transaction;
        this.connections = connections;
        this.internalIds = internalIds;
        this.skippedSuppliers = skippedSuppliers;
        this.invoices = invoices;
        this.stores = stores;
        this.users = users;
        this.credentials = credentials;
        this.cipher = cipher;
        this.sessions = sessions;
        this.mapper = mapper;
        this.storeAccess = storeAccess;
    }

    @Transactional(readOnly = true)
    public KsefSettingsResponse settings(UUID storeId) {
        Set<String> assigned = internalIds.findActive(storeId).stream().map(KsefInternalId::getInternalId)
                .collect(Collectors.toSet());
        KsefConnection connection = connections.findById(storeId).orElse(null);
        Store store = store(storeId);
        List<Store> otherStores = connection == null ? List.of() : stores.findActiveOrdered().stream()
                .filter(other -> !other.getId().equals(storeId) && canAccess(other.getId()))
                .toList();
        return new KsefSettingsResponse(
                store.getNip(),
                connection != null ? mapper.toResponse(connection) : null,
                internalIds.findActive(storeId).stream().map(mapper::toResponse).toList(),
                connection == null ? List.of() : invoices.findSeenInternalIds(connection.getNip()).stream()
                        .map(seen -> new KsefSeenInternalId(seen.getInternalId(), seen.getName(),
                                seen.getInvoiceCount(), assigned.contains(seen.getInternalId())))
                        .toList(),
                skippedSuppliers.findActive(storeId).stream().map(mapper::toResponse).toList(),
                companies(store),
                connection == null ? List.of() : companyStores(otherStores, connection.getNip()),
                (int) otherStores.stream().filter(other -> other.getNip() == null).count());
    }

    /** Inne sklepy użytkownika z NIP-em firmy połączenia: już połączone i te, które można podłączyć. */
    private List<KsefCompanyStore> companyStores(List<Store> candidates, String nip) {
        List<Store> sameCompany = candidates.stream().filter(store -> nip.equals(store.getNip())).toList();
        Set<UUID> connected = connections.findAllById(sameCompany.stream().map(Store::getId).toList()).stream()
                .filter(connection -> connection.getNip().equals(nip))
                .map(KsefConnection::getStoreId)
                .collect(Collectors.toSet());
        return sameCompany.stream()
                .map(store -> new KsefCompanyStore(store.getId(), store.getNumber(), store.getName(),
                        connected.contains(store.getId())))
                .toList();
    }

    /**
     * Firma sklepu (po NIP-ie z ustawień sklepu), jeśli jest połączona z KSeF w innym sklepie zalogowanego
     * użytkownika - do podłączenia sklepu bez certyfikatu. Sklep bez NIP-u niczego nie dostaje, bo nie wiadomo,
     * do której firmy należy.
     */
    private List<KsefCompanyResponse> companies(Store store) {
        if (store.getNip() == null) {
            return List.of();
        }
        List<KsefConnection> others = connections.findByNip(store.getNip()).stream()
                .filter(connection -> !connection.getStoreId().equals(store.getId()))
                .filter(connection -> canAccess(connection.getStoreId()))
                .toList();
        Map<UUID, String> names = stores.findAllById(others.stream().map(KsefConnection::getStoreId).toList())
                .stream().collect(Collectors.toMap(Store::getId, Store::getName));
        return others.stream()
                .collect(Collectors.groupingBy(KsefConnection::getNip, TreeMap::new,
                        Collectors.mapping(connection -> names.get(connection.getStoreId()), Collectors.toList())))
                .entrySet().stream()
                .map(entry -> new KsefCompanyResponse(entry.getKey(),
                        entry.getValue().stream().filter(Objects::nonNull).sorted().toList()))
                .toList();
    }

    /**
     * Zapisuje połączenie i od razu je testuje. Przy zmianie samego NIP-u pliki i hasło mogą zostać puste (zostają
     * zapisane); nowy klucz bez hasła próbuje zapisanego hasła.
     */
    public KsefSettingsResponse saveConnection(UUID storeId, String nip, MultipartFile certificateFile,
            MultipartFile keyFile, String password, UUID userId) {
        String cleanNip = Nip.parse(nip, "nip");
        String previousNip = connections.findById(storeId).map(KsefConnection::getNip).orElse(null);
        transaction.executeWithoutResult(status -> {
            Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
            KsefConnection connection = connections.findById(storeId).orElse(null);
            if (connection == null) {
                ensureStoreCompany(store, cleanNip);
            }
            if (connection == null && (empty(certificateFile) || empty(keyFile))) {
                throw ApiException.badRequest("Wgraj certyfikat KSeF (.crt) i jego klucz (.key)").withProperty("errors",
                        Map.of(empty(certificateFile) ? "certificate" : "privateKey", "Wgraj plik"));
            }
            String certificatePem = empty(certificateFile) ? connection.getCertificatePem()
                    : credentials.toPem(credentials.certificate(bytes(certificateFile)));
            String keyPem = empty(keyFile) ? connection.getPrivateKeyPem()
                    : new String(bytes(keyFile), StandardCharsets.US_ASCII);
            String plainPassword = password != null && !password.isEmpty() ? password
                    : connection != null ? cipher.decrypt(connection.getPasswordEncrypted()) : null;

            X509Certificate certificate = credentials.load(certificatePem, keyPem, plainPassword).certificate();
            if (certificate.getNotAfter().toInstant().isBefore(Instant.now())) {
                throw ApiException.badRequest("Certyfikat wygasł - wygeneruj nowy w Aplikacji Podatnika KSeF")
                        .withProperty("errors", Map.of("certificate", "Certyfikat wygasł"));
            }
            if (connection == null) {
                connection = new KsefConnection(store.getId());
            }
            connection.setCredentials(cleanNip, certificatePem, keyPem, cipher.encrypt(plainPassword),
                    subjectName(certificate), certificate.getNotAfter().toInstant(), user(userId));
            connections.save(connection);
            // Certyfikat firmy potwierdza, do której firmy należy sklep: uzupełnia brakujący NIP, a przy wymianie
            // połączenia na inną firmę go zmienia (w ustawieniach sklepu zmiany NIP-u połączonego sklepu nie ma).
            store.setNip(cleanNip);
        });
        // Token jest wspólny dla firmy - nowe dane logowania (albo inny NIP) go unieważniają.
        sessions.forget(cleanNip);
        if (previousNip != null) {
            sessions.forget(previousNip);
        }
        return test(storeId);
    }

    /**
     * Podłącza sklep do firmy połączonej już z KSeF w innym sklepie: kopiuje jej dane logowania, więc sklep od razu
     * widzi wspólne faktury firmy. Źródłem może być tylko sklep, do którego użytkownik ma dostęp: certyfikat firmy
     * kopiuje ten, kto i tak mógłby w nim pracować.
     */
    public KsefSettingsResponse linkConnection(UUID storeId, String nip, UUID userId) {
        String cleanNip = Nip.parse(nip, "nip");
        String previousNip = connections.findById(storeId).map(KsefConnection::getNip).orElse(null);
        transaction.executeWithoutResult(status -> {
            Store store = store(storeId);
            if (store.getNip() == null) {
                throw ApiException.badRequest("Sklep nie ma NIP-u firmy - właściciel wpisuje go w Ustawieniach, "
                        + "w zakładce Sklepy");
            }
            ensureStoreCompany(store, cleanNip);
            KsefConnection source = connections.findByNip(cleanNip).stream()
                    .filter(connection -> !connection.getStoreId().equals(storeId))
                    .filter(connection -> canAccess(connection.getStoreId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("Ta firma nie jest połączona z KSeF w innym sklepie"));
            KsefConnection connection = connections.findById(storeId)
                    .orElseGet(() -> new KsefConnection(store.getId()));
            connection.copyCredentials(source, user(userId));
            connections.save(connection);
        });
        if (previousNip != null && !previousNip.equals(cleanNip)) {
            sessions.forget(previousNip);
        }
        return test(storeId);
    }

    /** Połączenie sklepu musi być z firmą z jego ustawień (gdy NIP jest wpisany). */
    private static void ensureStoreCompany(Store store, String nip) {
        if (store.getNip() != null && !store.getNip().equals(nip)) {
            String message = "Sklep należy do firmy o NIP " + store.getNip() + " (Ustawienia, zakładka Sklepy)";
            throw ApiException.badRequest(message).withProperty("errors", Map.of("nip", message));
        }
    }

    /** Czy zalogowany użytkownik ma dostęp do sklepu (właściciel: każdy, pracownik: przypisane aktywne). */
    private boolean canAccess(UUID storeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && storeAccess.canAccess(authentication, storeId);
    }

    /**
     * Podłącza inne sklepy użytkownika do firmy tego sklepu: kopiuje im dane logowania i wynik ostatniego testu (to ten
     * sam certyfikat i NIP, więc nie logujemy się do KSeF osobno dla każdego sklepu). Sklep połączony już z tą firmą
     * zostaje bez zmian; sklepu połączonego z inną firmą nie przełączamy.
     */
    public KsefSettingsResponse shareConnection(UUID storeId, List<UUID> storeIds, UUID userId) {
        transaction.executeWithoutResult(status -> {
            KsefConnection source = connections.findById(storeId)
                    .orElseThrow(() -> ApiException.notFound("Sklep nie ma jeszcze połączenia z KSeF"));
            AppUser by = user(userId);
            for (UUID targetId : storeIds.stream().distinct().filter(id -> !id.equals(storeId)).toList()) {
                Store target = stores.findById(targetId).filter(store -> canAccess(store.getId()))
                        .orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
                if (!source.getNip().equals(target.getNip())) {
                    throw ApiException.conflict(target.getNip() == null
                            ? "Sklep " + target.getName() + " nie ma NIP-u firmy w ustawieniach sklepu"
                            : "Sklep " + target.getName() + " należy do innej firmy (NIP " + target.getNip() + ")");
                }
                KsefConnection existing = connections.findById(targetId).orElse(null);
                if (existing != null && existing.getNip().equals(source.getNip())) {
                    continue;
                }
                if (existing != null) {
                    throw ApiException.conflict("Sklep " + target.getName() + " jest połączony z inną firmą (NIP "
                            + existing.getNip() + ")");
                }
                KsefConnection connection = new KsefConnection(target.getId());
                connection.copyCredentials(source, by);
                if (source.getTestOk() != null) {
                    connection.recordTest(source.getTestOk(), source.getTestMessage());
                }
                connections.save(connection);
            }
        });
        return transaction.execute(status -> settings(storeId));
    }

    /** Logowanie do KSeF zapisanymi danymi; wynik zostaje zapisany przy połączeniu. */
    public KsefSettingsResponse test(UUID storeId) {
        KsefConnection connection = connections.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Sklep nie ma jeszcze połączenia z KSeF"));
        boolean ok;
        String message;
        try {
            sessions.login(connection);
            ok = true;
            message = "Zalogowano do KSeF certyfikatem " + connection.getCertificateSubject() + " w kontekście NIP "
                    + connection.getNip();
        } catch (KsefClient.KsefException | ApiException | IllegalStateException e) {
            ok = false;
            message = e.getMessage();
        }
        boolean result = ok;
        String saved = truncate(message);
        return transaction.execute(status -> {
            connections.findById(storeId).ifPresent(current -> current.recordTest(result, saved));
            return settings(storeId);
        });
    }

    @Transactional
    public KsefSettingsResponse addInternalId(UUID storeId, String internalId, String name, UUID userId) {
        String clean = internalId.trim();
        connections.findById(storeId).ifPresent(connection -> {
            if (!clean.startsWith(connection.getNip() + "-")) {
                throw ApiException.badRequest("IDWew zaczyna się NIP-em firmy z połączenia (" + connection.getNip() + ")")
                        .withProperty("errors", Map.of("internalId", "Inny NIP niż w połączeniu"));
            }
        });
        if (internalIds.existsActive(storeId, clean)) {
            throw ApiException.conflict("Ten IDWew jest już na liście sklepu");
        }
        internalIds.save(new KsefInternalId(store(storeId), clean, blankToNull(name), user(userId)));
        return settings(storeId);
    }

    @Transactional
    public KsefSettingsResponse removeInternalId(UUID storeId, UUID id, UUID userId) {
        internalIds.findInStore(storeId, id).filter(item -> item.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono IDWew"))
                .markRemoved(user(userId));
        return settings(storeId);
    }

    @Transactional
    public KsefSettingsResponse restoreInternalId(UUID storeId, UUID id) {
        KsefInternalId item = internalIds.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono IDWew"));
        if (item.getRemovedAt() != null && internalIds.existsActive(storeId, item.getInternalId())) {
            throw ApiException.conflict("Ten IDWew jest już na liście sklepu");
        }
        item.restore();
        return settings(storeId);
    }

    @Transactional
    public KsefSettingsResponse addSkippedSupplier(UUID storeId, String nip, String name, UUID userId) {
        String clean = Nip.parse(nip, "nip");
        if (skippedSuppliers.existsActive(storeId, clean)) {
            throw ApiException.conflict("Ten dostawca jest już pomijany");
        }
        skippedSuppliers.save(new KsefSkippedSupplier(store(storeId), clean, blankToNull(name), user(userId)));
        return settings(storeId);
    }

    @Transactional
    public KsefSettingsResponse removeSkippedSupplier(UUID storeId, UUID id, UUID userId) {
        skippedSuppliers.findInStore(storeId, id).filter(item -> item.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"))
                .markRemoved(user(userId));
        return settings(storeId);
    }

    @Transactional
    public KsefSettingsResponse restoreSkippedSupplier(UUID storeId, UUID id) {
        KsefSkippedSupplier item = skippedSuppliers.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
        if (item.getRemovedAt() != null && skippedSuppliers.existsActive(storeId, item.getNip())) {
            throw ApiException.conflict("Ten dostawca jest już pomijany");
        }
        item.restore();
        return settings(storeId);
    }

    /** CN certyfikatu, np. "JAN KOWALSKI (uwierzytelnienie)", a bez niego cały podmiot. */
    private static String subjectName(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName();
        try {
            return new LdapName(subject).getRdns().stream()
                    .filter(rdn -> rdn.getType().equalsIgnoreCase("CN"))
                    .map(Rdn::getValue).map(Object::toString)
                    .findFirst().map(KsefConnectionService::truncateSubject).orElse(truncateSubject(subject));
        } catch (InvalidNameException e) {
            return truncateSubject(subject);
        }
    }

    private static String truncateSubject(String subject) {
        return subject.length() > 300 ? subject.substring(0, 300) : subject;
    }

    static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static boolean empty(MultipartFile file) {
        return file == null || file.isEmpty();
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku " + file.getOriginalFilename());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
