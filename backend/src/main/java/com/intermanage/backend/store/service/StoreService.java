package com.intermanage.backend.store.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.common.component.Nip;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.repository.StaffDepartmentRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.store.dto.StoreRequest;
import com.intermanage.backend.store.dto.StoreResponse;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StoreRepository;


@Service
public class StoreService {

    private final StoreRepository stores;
    private final StoreMapper storeMapper;
    private final KsefConnectionRepository ksefConnections;
    private final StaffDepartmentRepository staffDepartments;
    private final StaffEmployeeRepository staffEmployees;

    public StoreService(StoreRepository stores, StoreMapper storeMapper, KsefConnectionRepository ksefConnections,
            StaffDepartmentRepository staffDepartments, StaffEmployeeRepository staffEmployees) {
        this.stores = stores;
        this.storeMapper = storeMapper;
        this.ksefConnections = ksefConnections;
        this.staffDepartments = staffDepartments;
        this.staffEmployees = staffEmployees;
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> list() {
        Map<UUID, Long> counts = userCounts();
        return stores.findAllOrdered().stream()
                .map(store -> storeMapper.toResponse(store, counts.getOrDefault(store.getId(), 0L)))
                .toList();
    }

    @Transactional
    public StoreResponse create(StoreRequest request) {
        String number = request.number().trim();
        if (stores.existsByNumber(number)) {
            throw ApiException.conflict("Sklep o numerze " + number + " już istnieje");
        }
        Store store = new Store(number, request.name().trim(), blankToNull(request.address()));
        store.setNip(nip(request.nip()));
        store.setActive(request.active());
        return storeMapper.toResponse(stores.save(store), 0);
    }

    @Transactional
    public StoreResponse update(UUID id, StoreRequest request) {
        Store store = stores.findById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String number = request.number().trim();
        if (stores.existsByNumberAndIdNot(number, id)) {
            throw ApiException.conflict("Sklep o numerze " + number + " już istnieje");
        }
        store.setNumber(number);
        store.setName(request.name().trim());
        store.setAddress(blankToNull(request.address()));
        String nip = nip(request.nip());
        String connectedNip = ksefConnections.findById(id).map(KsefConnection::getNip).orElse(null);
        if (connectedNip != null && !connectedNip.equals(nip)) {
            throw ApiException.conflict("Sklep jest połączony z KSeF firmy o NIP " + connectedNip
                    + " - najpierw zmień połączenie w ustawieniach faktur KSeF").withProperty("errors",
                    Map.of("nip", "Sklep jest połączony z KSeF firmy o NIP " + connectedNip));
        }
        changeStaffCompany(store, nip);
        store.setNip(nip);
        store.setActive(request.active());
        return storeMapper.toResponse(store, userCounts().getOrDefault(id, 0L));
    }

    /**
     * Działy i stanowiska należą do firmy. Sklep bez NIP-u był firmą sam dla siebie, więc po wpisaniu NIP-u jego działy
     * przechodzą do firmy. Zmiana firmy sklepu z pracownikami zostawiłaby ich na stanowiskach poprzedniej.
     */
    private void changeStaffCompany(Store store, String nip) {
        if (Objects.equals(store.getNip(), nip)) {
            return;
        }
        if (store.getNip() != null) {
            if (staffEmployees.countActiveInStore(store.getId()) > 0) {
                throw ApiException.conflict("Sklep ma pracowników firmy o NIP " + store.getNip()
                        + ". Najpierw przenieś ich do innego sklepu tej firmy.");
            }
            return;
        }
        if (nip == null) {
            return;
        }
        for (StaffDepartment department : staffDepartments.findAllInCompany(store.companyKey())) {
            if (department.getDeletedAt() == null && staffDepartments.existsActiveName(nip, department.getName(), null)) {
                throw ApiException.conflict("Firma ma już dział „" + department.getName()
                        + "”. Zmień nazwę działu w tym sklepie.");
            }
            department.setCompanyKey(nip);
        }
    }

    /** Sklepy do wyboru: właściciel widzi wszystkie aktywne, pracownik tylko przypisane aktywne. */
    @Transactional(readOnly = true)
    public List<StoreSummary> accessibleStores(AppUser user) {
        List<Store> result = user.getRole().isOwner()
                ? stores.findActiveOrdered()
                : user.getStores().stream()
                        .filter(Store::isActive)
                        .sorted(Comparator.comparing(Store::getNumber))
                        .toList();
        return result.stream().map(storeMapper::toSummary).toList();
    }

    /** Sklepy z listy id z formularza; nieistniejące id to błąd. */
    @Transactional(readOnly = true)
    public List<Store> loadAll(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Store> found = stores.findAllById(ids);
        if (found.size() != ids.stream().distinct().count()) {
            throw ApiException.badRequest("Wybrany sklep nie istnieje");
        }
        return found;
    }

    private Map<UUID, Long> userCounts() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : stores.countUsersPerStore()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** NIP z formularza: pusty = brak, inaczej 10 cyfr z poprawną sumą kontrolną (myślniki i spacje pomijamy). */
    private static String nip(String value) {
        return value == null || value.isBlank() ? null : Nip.parse(value, "nip");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
