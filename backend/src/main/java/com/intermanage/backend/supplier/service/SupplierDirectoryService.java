package com.intermanage.backend.supplier.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.dto.CreateSupplierRequest;
import com.intermanage.backend.supplier.dto.SupplierContactRequest;
import com.intermanage.backend.supplier.dto.SupplierContactResponse;
import com.intermanage.backend.supplier.dto.SupplierDetails;
import com.intermanage.backend.supplier.dto.SupplierDetailsRequest;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.entity.SupplierContact;
import com.intermanage.backend.supplier.entity.SupplierContactKind;
import com.intermanage.backend.supplier.entity.SupplierProfile;
import com.intermanage.backend.supplier.mapper.SupplierMapper;
import com.intermanage.backend.supplier.repository.SupplierContactRepository;
import com.intermanage.backend.supplier.repository.SupplierProfileRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Kartoteka dostawców sklepu: dostawcy z listy z systemu sklepu i dodani ręcznie, a przy każdym strona internetowa,
 * telefon, e-mail, notatki i kontakty (przedstawiciele, biuro, księgowość). Te dane są w profilu wspólnym dla sklepów
 * firmy (ten sam NIP sklepu), a dostawcę w każdym sklepie wskazuje klucz ({@link #supplierKey}): kod z systemu sklepu,
 * u dodanego ręcznie NIP albo nazwa. Profil powstaje przy pierwszej zmianie i zostaje przypięty do dostawcy, więc
 * zmiana nazwy go nie odcina. Usunięcie kontaktu to znacznik.
 */
@Service
public class SupplierDirectoryService {

    private final SupplierRepository suppliers;
    private final SupplierProfileRepository profiles;
    private final SupplierContactRepository contacts;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SupplierMapper mapper;

    public SupplierDirectoryService(SupplierRepository suppliers, SupplierProfileRepository profiles,
            SupplierContactRepository contacts, StoreRepository stores, AppUserRepository users,
            SupplierMapper mapper) {
        this.suppliers = suppliers;
        this.profiles = profiles;
        this.contacts = contacts;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    /** Wszyscy dostawcy sklepu po nazwie; jest ich kilkuset, więc frontend filtruje ich sam. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> list(UUID storeId) {
        Store store = store(storeId);
        Map<UUID, SupplierProfile> byId = new HashMap<>();
        Map<String, SupplierProfile> byKey = new HashMap<>();
        profiles.findByCompany(companyKey(store)).forEach(profile -> {
            byId.put(profile.getId(), profile);
            byKey.put(profile.getSupplierKey(), profile);
        });
        Map<UUID, List<SupplierContact>> byProfile = new HashMap<>();
        if (!byId.isEmpty()) {
            contacts.findActive(byId.keySet()).forEach(contact -> byProfile
                    .computeIfAbsent(contact.getProfile().getId(), id -> new ArrayList<>()).add(contact));
        }
        return suppliers.findByStore(storeId).stream().map(supplier -> {
            SupplierProfile profile = supplier.getProfile() != null
                    ? byId.get(supplier.getProfile().getId())
                    : byKey.get(supplierKey(supplier));
            List<SupplierContact> own = profile == null ? List.of()
                    : byProfile.getOrDefault(profile.getId(), List.of());
            return mapper.toResponse(supplier, profile, own.size(), representative(own));
        }).toList();
    }

    @Transactional(readOnly = true)
    public SupplierDetails details(UUID storeId, UUID supplierId) {
        Supplier supplier = supplier(storeId, supplierId);
        return details(supplier, existingProfile(supplier));
    }

    /**
     * Dostawca, którego nie ma w systemie sklepu - jest tylko w aplikacji. Gdy inny sklep firmy opisał już dostawcę
     * o tym NIP-ie (albo nazwie), nowy od razu ma jego dane.
     */
    @Transactional
    public SupplierResponse create(UUID storeId, CreateSupplierRequest request, UUID userId) {
        Store store = store(storeId);
        String name = singleLine(request.name());
        requireFreeName(storeId, name, null);
        Supplier manual = Supplier.manual(store, name, nip(request.nip()), user(userId));
        manual.updateManualData(name, manual.getNip(), singleLine(request.address()), singleLine(request.postalCode()),
                singleLine(request.city()));
        Supplier supplier = suppliers.save(manual);
        SupplierProfile profile = existingProfile(supplier);
        List<SupplierContact> own = profile == null ? List.of() : contacts.findActive(profile.getId());
        return mapper.toResponse(supplier, profile, own.size(), representative(own));
    }

    /** Strona, telefon, e-mail i notatki (dla całej firmy); u dostawcy dodanego ręcznie też nazwa, NIP i adres. */
    @Transactional
    public SupplierDetails update(UUID storeId, UUID supplierId, SupplierDetailsRequest request, UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        if (supplier.isManual()) {
            String name = singleLine(request.name());
            if (name == null) {
                throw ApiException.badRequest("Podaj nazwę dostawcy")
                        .withProperty("errors", Map.of("name", "Podaj nazwę dostawcy"));
            }
            requireFreeName(storeId, name, supplierId);
            // Profil przypinamy przed zmianą nazwy i NIP-u, bo po nich wskazuje go klucz dostawcy.
            profileForChange(supplier);
            supplier.updateManualData(name, nip(request.nip()), singleLine(request.address()),
                    singleLine(request.postalCode()), singleLine(request.city()));
        }
        SupplierProfile profile = profileForChange(supplier);
        profile.update(website(request.website()), singleLine(request.email()), singleLine(request.phone()),
                text(request.notes()), user(userId));
        suppliers.flush();
        return details(supplier, profile);
    }

    @Transactional
    public SupplierDetails addContact(UUID storeId, UUID supplierId, SupplierContactRequest request, UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        SupplierProfile profile = profileForChange(supplier);
        SupplierContact contact = new SupplierContact(profile, user(userId));
        apply(contact, request, null);
        contacts.saveAndFlush(contact);
        return details(supplier, profile);
    }

    @Transactional
    public SupplierDetails updateContact(UUID storeId, UUID supplierId, UUID contactId, SupplierContactRequest request,
            UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        SupplierProfile profile = existingProfile(supplier);
        SupplierContact contact = contact(profile, contactId);
        if (contact.getDeletedAt() != null) {
            throw ApiException.notFound("Nie znaleziono kontaktu");
        }
        apply(contact, request, user(userId));
        contacts.flush();
        return details(supplier, profile);
    }

    /** Usunięcie kontaktu to znacznik; {@link #restoreContact} go przywraca. */
    @Transactional
    public SupplierDetails deleteContact(UUID storeId, UUID supplierId, UUID contactId, UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        SupplierProfile profile = existingProfile(supplier);
        SupplierContact contact = contact(profile, contactId);
        if (contact.getDeletedAt() == null) {
            contact.markDeleted(user(userId));
            contacts.flush();
        }
        return details(supplier, profile);
    }

    @Transactional
    public SupplierDetails restoreContact(UUID storeId, UUID supplierId, UUID contactId) {
        Supplier supplier = supplier(storeId, supplierId);
        SupplierProfile profile = existingProfile(supplier);
        SupplierContact contact = contact(profile, contactId);
        if (contact.getDeletedAt() != null) {
            contact.restore();
            contacts.flush();
        }
        return details(supplier, profile);
    }

    /** Kontakty według rodzaju (przedstawiciele na górze), w rodzaju od najstarszego. */
    private SupplierDetails details(Supplier supplier, SupplierProfile profile) {
        List<SupplierContactResponse> profileContacts = profile == null ? List.of()
                : contacts.findActive(profile.getId()).stream()
                        .sorted(Comparator.comparing(SupplierContact::getKind))
                        .map(mapper::toResponse)
                        .toList();
        return mapper.toDetails(supplier, profile, profileContacts, sharedWithStores(supplier.getStore()));
    }

    private static SupplierContact representative(List<SupplierContact> contacts) {
        return contacts.stream().filter(contact -> contact.getKind() == SupplierContactKind.REPRESENTATIVE)
                .findFirst().orElse(null);
    }

    /** Pozostałe aktywne sklepy firmy, które widzą te same dane dostawcy. */
    private List<String> sharedWithStores(Store store) {
        if (store.getNip() == null) {
            return List.of();
        }
        return stores.findActiveOrdered().stream()
                .filter(other -> !other.getId().equals(store.getId()) && store.getNip().equals(other.getNip()))
                .map(other -> other.getName() + " (" + other.getNumber() + ")")
                .toList();
    }

    /** Profil przypięty do dostawcy albo profil firmy o jego kluczu; {@code null}, gdy nikt go jeszcze nie opisał. */
    private SupplierProfile existingProfile(Supplier supplier) {
        if (supplier.getProfile() != null) {
            return supplier.getProfile();
        }
        return profiles.findByKeys(companyKey(supplier.getStore()), supplierKey(supplier)).orElse(null);
    }

    /** Profil do zmiany: istniejący albo nowy, zawsze przypięty do dostawcy. */
    private SupplierProfile profileForChange(Supplier supplier) {
        SupplierProfile profile = existingProfile(supplier);
        if (profile == null) {
            profile = profiles.save(new SupplierProfile(companyKey(supplier.getStore()), supplierKey(supplier)));
        }
        if (supplier.getProfile() == null) {
            supplier.linkProfile(profile);
        }
        return profile;
    }

    private static String companyKey(Store store) {
        return store.companyKey();
    }

    /** Ten sam dostawca w sklepach firmy: kod z systemu sklepu, u dodanego ręcznie NIP albo nazwa. */
    private static String supplierKey(Supplier supplier) {
        if (supplier.getCode() != null) {
            return "code:" + supplier.getCode();
        }
        if (supplier.getNip() != null) {
            return "nip:" + supplier.getNip();
        }
        return "name:" + supplier.getName().toLowerCase(Locale.ROOT);
    }

    private static void apply(SupplierContact contact, SupplierContactRequest request, AppUser by) {
        String name = singleLine(request.name());
        String phone = singleLine(request.phone());
        String email = singleLine(request.email());
        if (name == null && phone == null && email == null) {
            throw ApiException.badRequest("Podaj imię i nazwisko, telefon albo e-mail");
        }
        contact.update(request.kind(), name, singleLine(request.jobTitle()), phone, email, text(request.notes()), by);
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private Supplier supplier(UUID storeId, UUID supplierId) {
        return suppliers.findInStore(storeId, supplierId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
    }

    private SupplierContact contact(SupplierProfile profile, UUID contactId) {
        if (profile == null) {
            throw ApiException.notFound("Nie znaleziono kontaktu");
        }
        return contacts.findOfProfile(profile.getId(), contactId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kontaktu"));
    }

    private void requireFreeName(UUID storeId, String name, UUID exceptId) {
        if (suppliers.existsManualByName(storeId, name, exceptId)) {
            throw ApiException.conflict("Dostawca " + name + " jest już na liście");
        }
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }

    /** Bez zbędnych spacji; pusty tekst to {@code null}. */
    private static String singleLine(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nip(String value) {
        String digits = value == null ? "" : value.replaceAll("[\\s-]", "");
        return digits.isEmpty() ? null : digits;
    }

    /** Adres strony z protokołem, żeby link działał: "sokolow.pl" -> "https://sokolow.pl". */
    private static String website(String value) {
        String url = singleLine(value);
        if (url == null) {
            return null;
        }
        if (url.contains(" ")) {
            throw ApiException.badRequest("Adres strony nie może zawierać spacji")
                    .withProperty("errors", Map.of("website", "Adres strony nie może zawierać spacji"));
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? url : "https://" + url;
    }
}
