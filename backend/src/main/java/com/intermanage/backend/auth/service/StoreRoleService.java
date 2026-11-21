package com.intermanage.backend.auth.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.component.StoreRoleDefaults;
import com.intermanage.backend.auth.dto.StoreRoleOfferRequest;
import com.intermanage.backend.auth.dto.StoreRoleOrderRequest;
import com.intermanage.backend.auth.dto.StoreRoleRequest;
import com.intermanage.backend.auth.dto.StoreRoleResponse;
import com.intermanage.backend.auth.dto.StoreRolesResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.entity.StoreRoleOffer;
import com.intermanage.backend.auth.entity.UserStoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.StoreRoleOfferRepository;
import com.intermanage.backend.auth.repository.StoreRoleRepository;
import com.intermanage.backend.auth.repository.UserStoreRoleRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.repository.StaffPositionRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Hierarchia sklepu: rangi z uprawnieniami ułożone od najwyższej i ranga każdego konta w sklepie.
 *
 * <ul>
 * <li>Właściciel aplikacji stoi ponad wszystkim. Aderon ma w swoim sklepie wszystkie uprawnienia i zarządza każdym,
 * poza właścicielem.</li>
 * <li>Wyżej w hierarchii = zarządza kontami i rangami niżej od siebie (rangi zmienia z {@code STAFF:EDIT}, uprawnienia
 * daje najwyżej takie, jakie ma sam) i zleca im zadania.</li>
 * <li>Rangi poza hierarchią (Księgowość, Kadry) mają tylko uprawnienia: nikim nie zarządzają, a nimi i ich rangami
 * zarządza Aderon i właściciel.</li>
 * </ul>
 */
@Service
public class StoreRoleService {

    private static final int OUTSIDE_POSITION = 1000;

    private final StoreRoleRepository ranks;
    private final UserStoreRoleRepository assignments;
    private final StoreRoleOfferRepository offers;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final StaffPositionRepository positions;
    private final JsonMapper json;

    public StoreRoleService(StoreRoleRepository ranks, UserStoreRoleRepository assignments,
            StoreRoleOfferRepository offers, AppUserRepository users, StoreRepository stores,
            StorePartnerRepository partners, StaffPositionRepository positions, JsonMapper json) {
        this.ranks = ranks;
        this.assignments = assignments;
        this.offers = offers;
        this.users = users;
        this.stores = stores;
        this.partners = partners;
        this.positions = positions;
        this.json = json;
    }

    /** Kto pyta: właściciel albo ranga w sklepie. */
    public record Actor(AppUser user, boolean owner, StoreRole rank) {

        public boolean admin() {
            return owner || (rank != null && rank.isAdmin());
        }

        public Map<PermissionCategory, PermissionLevel> permissions() {
            if (admin()) {
                Map<PermissionCategory, PermissionLevel> all = new EnumMap<>(PermissionCategory.class);
                for (PermissionCategory category : PermissionCategory.values()) {
                    all.put(category, PermissionLevel.EDIT);
                }
                return all;
            }
            return rank == null ? Map.of() : rank.effectivePermissions();
        }

        /** Zmienia rangi niżej od siebie: Aderon i właściciel każdą, inni z {@code STAFF:EDIT}. */
        public boolean editsHierarchy() {
            return admin() || permissions().get(PermissionCategory.STAFF) == PermissionLevel.EDIT;
        }

        /** Czy ta osoba stoi wyżej niż ranga {@code target} ({@code null} = konto bez rangi, czyli najniżej). */
        public boolean above(StoreRole target) {
            if (owner) {
                return true;
            }
            if (rank == null) {
                return false;
            }
            return rank.isAbove(target);
        }
    }

    @Transactional(readOnly = true)
    public Actor actor(UUID userId, UUID storeId) {
        AppUser user = users.findWithRoleById(userId).orElseThrow(() -> ApiException.notFound("Nie znaleziono konta"));
        return new Actor(user, user.isOwner(), user.isOwner() ? null : user.rankIn(storeId));
    }

    /** Właściciel albo Aderon sklepu. */
    @Transactional(readOnly = true)
    public boolean administers(UUID userId, UUID storeId) {
        return actor(userId, storeId).admin();
    }

    // ---------- kto pod kim ----------

    /** Czy {@code actorId} zarządza kontem {@code targetId} w sklepie (stoi wyżej). */
    @Transactional(readOnly = true)
    public boolean canManage(UUID actorId, UUID storeId, AppUser target) {
        Actor actor = actor(actorId, storeId);
        if (target.isOwner()) {
            return actor.owner();
        }
        if (actor.admin()) {
            return true;
        }
        if (target.getId().equals(actorId)) {
            return false;
        }
        StoreRole targetRank = target.rankIn(storeId);
        if (actor.rank() != null && actor.rank().isOutsideHierarchy()) {
            // Poza hierarchią (Kadry): prowadzi konta w hierarchii, ale nie Aderona i nie innych spoza niej.
            return targetRank == null || (!targetRank.isAdmin() && !targetRank.isOutsideHierarchy());
        }
        return actor.above(targetRank);
    }

    @Transactional(readOnly = true)
    public void requireManageable(UUID actorId, UUID storeId, AppUser target) {
        if (!canManage(actorId, storeId, target)) {
            throw ApiException.forbidden("Konto „" + target.getDisplayName() + "” nie stoi w hierarchii sklepu niżej "
                    + "od Ciebie. Taką zmianę może zrobić osoba wyżej, Aderon albo właściciel.");
        }
    }

    /**
     * Czy {@code actorId} może nadać rangę: właściciel i Aderon każdą, pozostali rangę w hierarchii nie wyższą od
     * własnej i bez uprawnień, których sami nie mają.
     */
    @Transactional(readOnly = true)
    public boolean canGrant(UUID actorId, StoreRole rank) {
        return grantable(actor(actorId, rank.getStore().getId()), rank);
    }

    @Transactional(readOnly = true)
    public void requireGrantable(UUID actorId, StoreRole rank) {
        if (!canGrant(actorId, rank)) {
            throw ApiException.forbidden("Ranga „" + rank.getName() + "” stoi wyżej od Twojej albo ma więcej "
                    + "uprawnień. Taką zmianę może zrobić Aderon albo właściciel.");
        }
    }

    private static boolean grantable(Actor actor, StoreRole rank) {
        if (actor.admin()) {
            return actor.owner() || !rank.isAdmin() || actor.rank().isAdmin();
        }
        StoreRole own = actor.rank();
        if (own == null || rank.isAdmin() || rank.isOutsideHierarchy()) {
            return false;
        }
        if (!own.isOutsideHierarchy() && !own.getId().equals(rank.getId()) && !own.isAbove(rank)) {
            return false;
        }
        return covers(actor.permissions(), rank.effectivePermissions());
    }

    private static boolean covers(Map<PermissionCategory, PermissionLevel> own,
            Map<PermissionCategory, PermissionLevel> wanted) {
        return wanted.entrySet().stream().allMatch(permission -> {
            PermissionLevel level = own.get(permission.getKey());
            return level != null && level.includes(permission.getValue());
        });
    }

    // ---------- ranga konta ----------

    /** Ranga konta w sklepie rangi (nadpisuje poprzednią). */
    @Transactional
    public void assign(AppUser user, StoreRole rank, UUID byId) {
        if (user.isOwner()) {
            return;
        }
        AppUser by = byId == null ? null : users.getReferenceById(byId);
        UserStoreRole assignment = assignments.find(user.getId(), rank.getStore().getId()).orElse(null);
        if (assignment == null) {
            assignment = assignments.save(new UserStoreRole(user, rank, by));
            user.getStoreRoles().add(assignment);
        } else if (!assignment.getRole().getId().equals(rank.getId())) {
            assignment.change(rank, by);
        }
    }

    /** Ranga o kluczu (np. ze stanowiska) w sklepie; brak takiej rangi w sklepie zostawia obecną. */
    @Transactional
    public void assignByKey(AppUser user, UUID storeId, String key, UUID byId) {
        if (user == null || key == null) {
            return;
        }
        ranks.findActiveByKey(storeId, key).ifPresent(rank -> assign(user, rank, byId));
    }

    /** Ranga sklepu do nadania; nieznana albo usunięta = 400. */
    @Transactional(readOnly = true)
    public StoreRole active(UUID storeId, UUID roleId) {
        return ranks.findInStore(storeId, roleId).filter(rank -> rank.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.badRequest("Wybrana ranga nie istnieje w tym sklepie"));
    }

    @Transactional(readOnly = true)
    public StoreRole activeByKey(UUID storeId, String key) {
        return key == null ? null : ranks.findActiveByKey(storeId, key).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<StoreRole> list(UUID storeId) {
        return ranks.findActive(storeId);
    }

    /** Domyślna ranga nowego konta: Pracownik, a bez niego najniższa w hierarchii. */
    @Transactional(readOnly = true)
    public StoreRole lowest(UUID storeId) {
        List<StoreRole> active = ranks.findActive(storeId);
        return active.stream().filter(rank -> StoreRoleDefaults.EMPLOYEE.equals(rank.getKey())).findFirst()
                .orElseGet(() -> active.stream().filter(rank -> !rank.isAdmin() && !rank.isOutsideHierarchy())
                        .max(Comparator.comparingInt(StoreRole::getPosition)).orElse(null));
    }

    // ---------- domyślne rangi ----------

    /** Zakłada domyślne rangi w sklepie, który nie ma jeszcze żadnej. */
    @Transactional
    public void ensureDefaults(Store store, UUID byId) {
        if (ranks.existsInStore(store.getId())) {
            return;
        }
        AppUser by = byId == null ? null : users.getReferenceById(byId);
        int position = 0;
        int outside = OUTSIDE_POSITION;
        for (StoreRoleDefaults.Definition definition : StoreRoleDefaults.ALL) {
            StoreRole rank = new StoreRole(store, definition.key(), definition.name(), definition.description(),
                    definition.admin(), definition.outsideHierarchy(),
                    definition.outsideHierarchy() ? outside++ : position++, by);
            rank.replacePermissions(definition.permissions());
            ranks.save(rank);
        }
        ranks.flush();
    }

    /**
     * Przeniesienie dawnych ról globalnych do sklepu: rola o nazwie domyślnej rangi oddaje jej swoje uprawnienia
     * (poza Aderonem, który ma wszystkie), pozostałe stają się rangami tuż nad Pracownikiem. Zwraca rangę dla
     * każdej roli.
     */
    @Transactional
    public Map<UUID, StoreRole> adoptLegacyRoles(Store store, List<Role> legacy) {
        ensureDefaults(store, null);
        Map<UUID, StoreRole> result = new HashMap<>();
        List<StoreRole> added = new ArrayList<>();
        for (Role role : legacy.stream().sorted(Comparator.comparing(role -> role.getName().toLowerCase())).toList()) {
            String key = legacyKey(role);
            StoreRole rank = ranks.findActiveByKey(store.getId(), key).orElse(null);
            if (rank == null) {
                String name = role.getName();
                if (ranks.nameTaken(store.getId(), name, null)) {
                    name = name + " (dawna rola)";
                }
                rank = ranks.save(new StoreRole(store, key, name, role.getDescription(), false, false, 0, null));
                added.add(rank);
            }
            if (!rank.isAdmin()) {
                rank.replacePermissions(role.getPermissions());
            }
            result.put(role.getId(), rank);
        }
        if (!added.isEmpty()) {
            List<StoreRole> order = new ArrayList<>(hierarchy(store.getId()));
            order.removeAll(added);
            int at = 0;
            while (at < order.size() && !StoreRoleDefaults.EMPLOYEE.equals(order.get(at).getKey())) {
                at++;
            }
            order.addAll(at, added);
            for (int i = 0; i < order.size(); i++) {
                order.get(i).setPosition(i + 1);
            }
        }
        ranks.flush();
        renumber(store.getId());
        return result;
    }

    /** Klucz rangi dawnej roli: domyślnej o tej nazwie albo własny, ten sam w każdym sklepie. */
    public static String legacyKey(Role role) {
        return StoreRoleDefaults.byName(role.getName()).map(StoreRoleDefaults.Definition::key)
                .orElse("legacy:" + role.getId());
    }

    // ---------- zmiany hierarchii ----------

    @Transactional(readOnly = true)
    public StoreRolesResponse get(UUID storeId, UUID actorId) {
        return response(storeId, actor(actorId, storeId));
    }

    @Transactional
    public StoreRolesResponse create(UUID storeId, StoreRoleRequest request, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        if (!actor.editsHierarchy()) {
            throw ApiException.forbidden("Rangi zmienia osoba z edycją Pracowników, Aderon albo właściciel");
        }
        String name = request.name().strip();
        requireFreeName(storeId, name, null);
        boolean outside = Boolean.TRUE.equals(request.outsideHierarchy());
        if (outside && !actor.admin()) {
            throw ApiException.forbidden("Rangę poza hierarchią zakłada Aderon albo właściciel");
        }
        List<StoreRole> hierarchy = hierarchy(storeId);
        int position;
        if (outside) {
            position = OUTSIDE_POSITION + ranks.findActive(storeId).size();
        } else {
            // Na miejscu najniższej rangi (zwykle Pracownik, która schodzi o jedno niżej), ale zawsze pod osobą,
            // która zakłada: najniższa ranga zakłada nową pod sobą.
            int floor = actor.admin() || actor.rank() == null ? 1 : actor.rank().getPosition() + 1;
            int lowest = hierarchy.isEmpty() ? 1 : hierarchy.get(hierarchy.size() - 1).getPosition();
            position = Math.max(floor, lowest);
        }
        StoreRole rank = new StoreRole(stores.getReferenceById(storeId), "custom:" + UUID.randomUUID(), name,
                blankToNull(request.description()), false, outside, position, users.getReferenceById(actorId));
        rank.replacePermissions(capped(actor, request.permissions()));
        // Ranga trafia przed tę, której miejsce zajmuje: przesuwamy niższe o jeden.
        if (!outside) {
            hierarchy.stream().filter(other -> other.getPosition() >= rank.getPosition())
                    .forEach(other -> other.setPosition(other.getPosition() + 1));
        }
        ranks.save(rank);
        ranks.flush();
        renumber(storeId);
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse update(UUID storeId, UUID roleId, StoreRoleRequest request, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        StoreRole rank = active(storeId, roleId);
        if (!editable(actor, rank)) {
            throw ApiException.forbidden("Zmieniasz tylko rangi niżej od swojej");
        }
        String name = request.name().strip();
        requireFreeName(storeId, name, roleId);
        rank.setName(name);
        rank.setDescription(blankToNull(request.description()));
        if (!rank.isAdmin()) {
            rank.replacePermissions(capped(actor, request.permissions()));
            Boolean outside = request.outsideHierarchy();
            if (actor.admin() && outside != null && rank.isOutsideHierarchy() != outside) {
                rank.setOutsideHierarchy(outside);
                rank.setPosition(outside ? OUTSIDE_POSITION + 999 : Integer.MAX_VALUE / 2);
            }
        }
        ranks.flush();
        renumber(storeId);
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse reorder(UUID storeId, StoreRoleOrderRequest request, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        if (!actor.editsHierarchy()) {
            throw ApiException.forbidden("Kolejność rang zmienia osoba z edycją Pracowników, Aderon albo właściciel");
        }
        List<StoreRole> current = hierarchy(storeId);
        Map<UUID, StoreRole> byId = new HashMap<>();
        current.forEach(rank -> byId.put(rank.getId(), rank));
        if (request.roleIds().size() != current.size() || !byId.keySet().equals(new HashSet<>(request.roleIds()))) {
            throw ApiException.conflict("Hierarchia zmieniła się w międzyczasie. Odśwież stronę i spróbuj ponownie.");
        }
        if (!actor.admin()) {
            // Rangi do własnej włącznie zostają na swoich miejscach: przestawia się tylko te pod sobą.
            int own = actor.rank() == null || actor.rank().isOutsideHierarchy() ? -1
                    : current.indexOf(actor.rank());
            if (own < 0) {
                throw ApiException.forbidden("Kolejność zmienia osoba stojąca w hierarchii");
            }
            for (int i = 0; i <= own; i++) {
                if (!current.get(i).getId().equals(request.roleIds().get(i))) {
                    throw ApiException.forbidden("Przestawiasz tylko rangi poniżej swojej");
                }
            }
        }
        for (int i = 0; i < request.roleIds().size(); i++) {
            byId.get(request.roleIds().get(i)).setPosition(i + 1);
        }
        ranks.flush();
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse delete(UUID storeId, UUID roleId, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        StoreRole rank = active(storeId, roleId);
        if (!editable(actor, rank) || rank.isAdmin()) {
            throw ApiException.forbidden("Tej rangi nie możesz usunąć");
        }
        long assigned = assignments.countActiveWithRole(roleId);
        if (assigned > 0) {
            throw ApiException.conflict("Rangę ma " + assigned + " kont. Najpierw zmień im rangę.");
        }
        long onPositions = positions.countActiveWithRoleKey(rank.getStore().companyKey(), rank.getKey());
        if (onPositions > 0) {
            throw ApiException.conflict("Rangę ustala " + onPositions + " stanowisk w Pracownikach. Najpierw zmień "
                    + "im rangę.");
        }
        rank.markDeleted(users.getReferenceById(actorId));
        ranks.flush();
        renumber(storeId);
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse restore(UUID storeId, UUID roleId, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        StoreRole rank = ranks.findInStore(storeId, roleId).orElseThrow(() -> ApiException.notFound("Nie ma takiej rangi"));
        if (rank.getDeletedAt() != null) {
            if (!actor.editsHierarchy() || (!actor.admin() && !actor.above(rank))) {
                throw ApiException.forbidden("Tej rangi nie możesz przywrócić");
            }
            requireFreeName(storeId, rank.getName(), roleId);
            if (ranks.findActiveByKey(storeId, rank.getKey()).isPresent()) {
                throw ApiException.conflict("Sklep ma już tę rangę");
            }
            rank.restore();
            ranks.flush();
            renumber(storeId);
        }
        return response(storeId, actor);
    }

    // ---------- przesyłanie hierarchii ----------

    /** Ranga w przesłanej hierarchii (JSON w {@code store_role_offers}). */
    public record OfferRole(String key, String name, String description, boolean admin, boolean outsideHierarchy,
            int position, Map<PermissionCategory, PermissionLevel> permissions) {
    }

    @Transactional
    public StoreRolesResponse offer(UUID storeId, StoreRoleOfferRequest request, UUID actorId) {
        Actor actor = requireAdmin(storeId, actorId);
        Store from = stores.getReferenceById(storeId);
        String payload = json.writeValueAsString(snapshot(storeId));
        for (UUID targetId : new HashSet<>(request.storeIds())) {
            if (targetId.equals(storeId) || !partners.arePartners(storeId, targetId)) {
                throw ApiException.badRequest("Hierarchię przesyła się tylko sklepom zaprzyjaźnionym");
            }
            Store target = stores.findById(targetId).orElseThrow(() -> ApiException.notFound("Nie ma takiego sklepu"));
            offers.findPending(storeId, targetId).ifPresent(previous -> previous.cancel(actor.user()));
            offers.flush();
            offers.save(new StoreRoleOffer(from, target, payload, actor.user()));
        }
        offers.flush();
        return response(storeId, actor);
    }

    /** Przyjęcie zastępuje rangi odbiorcy o tych samych kluczach i dopisuje brakujące; reszta zostaje pod nimi. */
    @Transactional
    public StoreRolesResponse accept(UUID storeId, UUID offerId, UUID actorId) {
        Actor actor = requireAdmin(storeId, actorId);
        StoreRoleOffer offer = pending(offerId);
        if (!offer.getToStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Hierarchię przyjmuje sklep, któremu ją przesłano");
        }
        String previous = json.writeValueAsString(snapshot(storeId));
        apply(storeId, read(offer.getPayload()), actor.user());
        offer.accept(previous, actor.user());
        offers.flush();
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse reject(UUID storeId, UUID offerId, UUID actorId) {
        Actor actor = requireAdmin(storeId, actorId);
        StoreRoleOffer offer = pending(offerId);
        if (!offer.getToStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Hierarchię odrzuca sklep, któremu ją przesłano");
        }
        offer.reject(actor.user());
        return response(storeId, actor);
    }

    @Transactional
    public StoreRolesResponse cancel(UUID storeId, UUID offerId, UUID actorId) {
        Actor actor = requireAdmin(storeId, actorId);
        StoreRoleOffer offer = pending(offerId);
        if (!offer.getFromStore().getId().equals(storeId)) {
            throw ApiException.forbidden("Propozycję wycofuje sklep, który ją wysłał");
        }
        offer.cancel(actor.user());
        return response(storeId, actor);
    }

    /** Cofnięcie przyjęcia: hierarchia sprzed przyjęcia, a rangi dopisane przez nie (bez kont) dostają znacznik. */
    @Transactional
    public StoreRolesResponse undo(UUID storeId, UUID offerId, UUID actorId) {
        Actor actor = requireAdmin(storeId, actorId);
        StoreRoleOffer offer = offers.findById(offerId).filter(StoreRoleOffer::canUndo)
                .filter(found -> found.getToStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Tego przyjęcia nie da się cofnąć"));
        List<OfferRole> previous = read(offer.getPreviousPayload());
        apply(storeId, previous, actor.user());
        Set<String> keys = new HashSet<>();
        previous.forEach(role -> keys.add(role.key()));
        for (StoreRole rank : ranks.findActive(storeId)) {
            if (!keys.contains(rank.getKey()) && assignments.countActiveWithRole(rank.getId()) == 0
                    && positions.countActiveWithRoleKey(rank.getStore().companyKey(), rank.getKey()) == 0) {
                rank.markDeleted(actor.user());
            }
        }
        offer.undo(actor.user());
        ranks.flush();
        renumber(storeId);
        return response(storeId, actor);
    }

    private void apply(UUID storeId, List<OfferRole> offered, AppUser by) {
        Map<String, StoreRole> existing = new LinkedHashMap<>();
        ranks.findActive(storeId).forEach(rank -> existing.put(rank.getKey(), rank));
        StoreRole admin = existing.values().stream().filter(StoreRole::isAdmin).findFirst().orElse(null);
        // Najpierw tymczasowe nazwy, bo dwie rangi mogą zamienić się nazwami (unikalne w sklepie).
        for (OfferRole role : offered) {
            StoreRole rank = role.admin() ? admin : existing.get(role.key());
            if (rank != null) {
                rank.setName("~" + rank.getId());
            }
        }
        ranks.flush();
        Set<String> taken = new HashSet<>();
        existing.values().forEach(rank -> taken.add(rank.getName().toLowerCase()));
        List<OfferRole> sorted = new ArrayList<>(offered);
        sorted.sort(Comparator.comparingInt(OfferRole::position));
        int position = 1;
        Set<UUID> placed = new HashSet<>();
        for (OfferRole role : sorted) {
            StoreRole rank = role.admin() ? admin : existing.get(role.key());
            if (rank == null && role.admin()) {
                continue;
            }
            String name = freeName(role.name(), taken);
            if (rank == null) {
                rank = new StoreRole(stores.getReferenceById(storeId), role.key(), name, role.description(), false,
                        role.outsideHierarchy(), 0, by);
                ranks.save(rank);
            } else {
                rank.setName(name);
                rank.setDescription(role.description());
                rank.setOutsideHierarchy(role.outsideHierarchy());
            }
            rank.replacePermissions(role.permissions() == null ? Map.of() : role.permissions());
            if (!rank.isAdmin()) {
                rank.setPosition(role.outsideHierarchy() ? OUTSIDE_POSITION + position : position);
                position++;
            }
            placed.add(rank.getId());
        }
        // Rangi odbiorcy spoza przesłanej hierarchii zostają, pod przesłanymi.
        for (StoreRole rank : existing.values()) {
            if (!rank.isAdmin() && rank.getId() != null && !placed.contains(rank.getId())) {
                rank.setPosition((rank.isOutsideHierarchy() ? OUTSIDE_POSITION : 0) + 500 + rank.getPosition());
            }
        }
        ranks.flush();
        renumber(storeId);
    }

    private static String freeName(String wanted, Set<String> taken) {
        String name = wanted.strip();
        String candidate = name;
        int number = 2;
        while (taken.contains(candidate.toLowerCase())) {
            candidate = name + " (" + number++ + ")";
        }
        taken.add(candidate.toLowerCase());
        return candidate;
    }

    private List<OfferRole> snapshot(UUID storeId) {
        return ranks.findActive(storeId).stream()
                .map(rank -> new OfferRole(rank.getKey(), rank.getName(), rank.getDescription(), rank.isAdmin(),
                        rank.isOutsideHierarchy(), rank.getPosition(), new HashMap<>(rank.getPermissions())))
                .toList();
    }

    private List<OfferRole> read(String payload) {
        return Arrays.asList(json.readValue(payload, OfferRole[].class));
    }

    private StoreRoleOffer pending(UUID offerId) {
        return offers.findById(offerId).filter(StoreRoleOffer::isPending)
                .orElseThrow(() -> ApiException.notFound("Ta propozycja jest już nieaktualna"));
    }

    private Actor requireAdmin(UUID storeId, UUID actorId) {
        Actor actor = actor(actorId, storeId);
        if (!actor.admin()) {
            throw ApiException.forbidden("Hierarchię przesyła i przyjmuje Aderon albo właściciel");
        }
        return actor;
    }

    // ---------- pomocnicze ----------

    private static boolean editable(Actor actor, StoreRole rank) {
        if (rank.isAdmin()) {
            return actor.owner();
        }
        if (actor.admin()) {
            return true;
        }
        return actor.editsHierarchy() && actor.above(rank);
    }

    /** Uprawnienia, które osoba może dać randze: najwyżej własne (Aderon i właściciel dowolne). */
    private static Map<PermissionCategory, PermissionLevel> capped(Actor actor,
            Map<PermissionCategory, PermissionLevel> requested) {
        Map<PermissionCategory, PermissionLevel> own = actor.permissions();
        Map<PermissionCategory, PermissionLevel> result = new HashMap<>();
        requested.forEach((category, level) -> {
            if (category == null || level == null) {
                return;
            }
            PermissionLevel max = own.get(category);
            if (max == null || !max.includes(level)) {
                throw ApiException.forbidden("Nie możesz dać randze uprawnienia „" + category.label() + ": "
                        + (level == PermissionLevel.EDIT ? "edycja" : "podgląd") + "”, którego sam nie masz");
            }
            result.put(category, level);
        });
        return result;
    }

    private List<StoreRole> hierarchy(UUID storeId) {
        return ranks.findActive(storeId).stream().filter(rank -> !rank.isAdmin() && !rank.isOutsideHierarchy())
                .sorted(Comparator.comparingInt(StoreRole::getPosition)).toList();
    }

    /** Aderon 0, hierarchia 1…n, poza hierarchią od 1000, bez dziur. */
    private void renumber(UUID storeId) {
        List<StoreRole> active = ranks.findActive(storeId);
        int position = 1;
        int outside = OUTSIDE_POSITION;
        List<StoreRole> sorted = new ArrayList<>(active);
        sorted.sort(Comparator.comparingInt(StoreRole::getPosition).thenComparing(rank -> rank.getName().toLowerCase()));
        for (StoreRole rank : sorted) {
            if (rank.isAdmin()) {
                rank.setPosition(0);
            } else if (rank.isOutsideHierarchy()) {
                rank.setPosition(outside++);
            } else {
                rank.setPosition(position++);
            }
        }
        ranks.flush();
    }

    private void requireFreeName(UUID storeId, String name, UUID exceptId) {
        if (ranks.nameTaken(storeId, name, exceptId)) {
            throw ApiException.conflict("Ranga o tej nazwie już jest w sklepie");
        }
    }

    private StoreRolesResponse response(UUID storeId, Actor actor) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : assignments.countPerRole(storeId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        List<StoreRoleResponse> roles = ranks.findActive(storeId).stream()
                .map(rank -> new StoreRoleResponse(rank.getId(), rank.getKey(), rank.getName(), rank.getDescription(),
                        rank.isAdmin(), rank.isOutsideHierarchy(), rank.getPosition(), rank.effectivePermissions(),
                        counts.getOrDefault(rank.getId(), 0L), editable(actor, rank),
                        editable(actor, rank) && !rank.isAdmin(), grantable(actor, rank)))
                .toList();
        List<StoreRoleOffer> pending = offers.findPending(storeId);
        return new StoreRolesResponse(roles, actor.rank() == null ? null : actor.rank().getId(), actor.admin(),
                actor.editsHierarchy(),
                pending.stream().filter(offer -> offer.getToStore().getId().equals(storeId))
                        .map(offer -> offer(offer, offer.getFromStore())).toList(),
                pending.stream().filter(offer -> offer.getFromStore().getId().equals(storeId))
                        .map(offer -> offer(offer, offer.getToStore())).toList());
    }

    private StoreRolesResponse.Offer offer(StoreRoleOffer offer, Store other) {
        List<String> names = read(offer.getPayload()).stream()
                .sorted(Comparator.comparing(OfferRole::admin).reversed().thenComparingInt(OfferRole::position))
                .map(OfferRole::name).toList();
        return new StoreRolesResponse.Offer(offer.getId(), other.getId(), other.getNumber(), other.getName(), names,
                offer.getCreatedAt(), offer.getCreatedBy() == null ? null : offer.getCreatedBy().getDisplayName());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
