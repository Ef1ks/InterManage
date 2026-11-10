package com.intermanage.backend.supplier.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.Nip;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.gus.component.GusClient;
import com.intermanage.backend.gus.dto.GusCompany;
import com.intermanage.backend.gus.service.GusService;
import com.intermanage.backend.supplier.dto.MercalysSupplierCreationRequest;
import com.intermanage.backend.supplier.dto.MercalysSupplierCreationResponse;
import com.intermanage.backend.supplier.dto.MercalysSupplierDraft;
import com.intermanage.backend.supplier.entity.MercalysSupplierCreation;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.mapper.SupplierMapper;
import com.intermanage.backend.supplier.repository.MercalysSupplierCreationRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Zakładanie dostawcy w Mercalys makrem (składa je przeglądarka): dane z GUS przycięte do pól programu i pierwszy wolny
 * kod sklepu. Kody sklepu idą po kolei od 1 (bazy i sklepy mają swoje, dużo wyższe), więc wolny to pierwsza liczba,
 * której nie ma ani na liście dostawców, ani w makrach skopiowanych przed wgraniem nowej listy.
 */
@Service
public class MercalysSupplierService {

    static final int NAME_LENGTH = 30;
    static final int KEYWORD_LENGTH = 8;

    private final SupplierRepository suppliers;
    private final MercalysSupplierCreationRepository creations;
    private final GusService gus;
    private final GusClient gusClient;
    private final SupplierMapper mapper;
    private final AppUserRepository users;

    public MercalysSupplierService(SupplierRepository suppliers, MercalysSupplierCreationRepository creations,
            GusService gus, GusClient gusClient, SupplierMapper mapper, AppUserRepository users) {
        this.suppliers = suppliers;
        this.creations = creations;
        this.gus = gus;
        this.gusClient = gusClient;
        this.mapper = mapper;
        this.users = users;
    }

    /** Dane do makra; dostawca o tym NIP-ie na liście sklepu = 409, bo nie ma czego zakładać. */
    @Transactional(readOnly = true)
    public MercalysSupplierDraft draft(UUID storeId, String nipValue, String invoiceName) {
        String nip = Nip.parse(nipValue, "nip");
        rejectKnown(storeId, nip);

        Optional<GusCompany> company = Optional.empty();
        String gusProblem = null;
        if (!gusClient.configured()) {
            gusProblem = "GUS nie jest skonfigurowany";
        } else {
            try {
                company = gus.find(nip);
                if (company.isEmpty()) {
                    gusProblem = "GUS nie ma firmy o tym NIP-ie";
                }
            } catch (GusClient.Unavailable exception) {
                gusProblem = "GUS nie odpowiada: " + exception.getMessage();
            }
        }

        String name = mercalysText(company.map(GusCompany::name).orElse(invoiceName == null ? "" : invoiceName));
        String[] names = splitName(name);
        MercalysSupplierCreationResponse previous = creations
                .findFirstByStoreIdAndNipAndDeletedAtIsNullOrderByCreatedAtDesc(storeId, nip).map(mapper::toResponse)
                .orElse(null);
        return new MercalysSupplierDraft(nip, freeCode(storeId), keyword(names[0]), names[0], names[1],
                cut(mercalysText(company.map(GusCompany::street).orElse("")), NAME_LENGTH),
                company.map(GusCompany::postalCode).orElse("").replaceAll("\\D", ""),
                cut(mercalysText(company.map(GusCompany::city).orElse("")), NAME_LENGTH),
                company.isPresent(), gusProblem, previous);
    }

    /** Makro skopiowane: kod jest zarezerwowany do wgrania listy dostawców. Zajęty kod = 409. */
    @Transactional
    public MercalysSupplierCreationResponse create(UUID storeId, MercalysSupplierCreationRequest request,
            UUID userId) {
        String nip = Nip.parse(request.nip(), "nip");
        rejectKnown(storeId, nip);
        String code = String.valueOf(Integer.parseInt(request.code()));
        if (usedCodes(storeId).contains(Integer.parseInt(code))) {
            throw ApiException.conflict("Kod " + code + " jest już zajęty, otwórz okno ponownie");
        }
        MercalysSupplierCreation creation = creations
                .saveAndFlush(new MercalysSupplierCreation(storeId, nip, code, request.name().trim(), user(userId)));
        return mapper.toResponse(creation);
    }

    /** Makro cofnięte (toast „Cofnij”): kod wraca do puli, wpis zostaje ze znacznikiem. */
    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        MercalysSupplierCreation creation = creations.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego makra dostawcy"));
        if (creation.getDeletedAt() == null) {
            creation.delete(user(userId));
        }
    }

    private void rejectKnown(UUID storeId, String nip) {
        suppliers.findByStore(storeId).stream()
                .filter(supplier -> supplier.getNip() != null && nip.equals(supplier.getNip().replaceAll("\\D", "")))
                .findFirst().ifPresent(supplier -> {
                    throw ApiException.conflict("Dostawca o NIP-ie " + nip + " jest już na liście dostawców"
                            + (supplier.getCode() == null ? "" : ": kod " + supplier.getCode()));
                });
    }

    String freeCode(UUID storeId) {
        Set<Integer> used = usedCodes(storeId);
        int code = 1;
        while (used.contains(code)) {
            code++;
        }
        return String.valueOf(code);
    }

    private Set<Integer> usedCodes(UUID storeId) {
        Set<Integer> used = new HashSet<>();
        for (Supplier supplier : suppliers.findByStore(storeId)) {
            addCode(used, supplier.getCode());
        }
        for (MercalysSupplierCreation creation : creations.findByStoreIdAndDeletedAtIsNull(storeId)) {
            addCode(used, creation.getCode());
        }
        return used;
    }

    private static void addCode(Set<Integer> used, String code) {
        if (code != null && code.matches("\\d{1,9}")) {
            used.add(Integer.parseInt(code));
        }
    }

    /** Wielkie litery bez polskich znaków i bez znaków, które psują makro, ze skróconą formą prawną. */
    static String mercalysText(String value) {
        String upper = value.toUpperCase(Locale.of("pl")).replaceAll("\\s+", " ").trim();
        upper = upper.replace("SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ", "SP. Z O.O.")
                .replace("SPÓŁKA KOMANDYTOWO-AKCYJNA", "S.K.A.")
                .replace("SPÓŁKA KOMANDYTOWA", "SP.K.")
                .replace("SPÓŁKA AKCYJNA", "S.A.")
                .replace("SPÓŁKA JAWNA", "SP.J.")
                .replace("SPÓŁKA CYWILNA", "S.C.")
                .replace("SPÓŁKA PARTNERSKA", "SP.P.");
        String plain = Normalizer.normalize(upper.replace('Ł', 'L'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[|;{}^+!#\"]", "").replaceAll("\\s+", " ").trim();
    }

    /** Nazwa w dwóch polach po 30 znaków, dzielona na granicy słowa. */
    static String[] splitName(String name) {
        if (name.length() <= NAME_LENGTH) {
            return new String[] { name, "" };
        }
        int split = name.lastIndexOf(' ', NAME_LENGTH);
        if (split <= 0) {
            split = NAME_LENGTH;
        }
        return new String[] { name.substring(0, split).trim(), cut(name.substring(split).trim(), NAME_LENGTH) };
    }

    /** Hasło: początek nazwy, do 8 znaków (Mercalys i tak by uciął). */
    static String keyword(String name) {
        return cut(name, KEYWORD_LENGTH);
    }

    private static String cut(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length).trim();
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
