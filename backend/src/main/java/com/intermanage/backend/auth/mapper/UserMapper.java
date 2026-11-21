package com.intermanage.backend.auth.mapper;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.dto.AvatarResponse;
import com.intermanage.backend.auth.dto.BackgroundResponse;
import com.intermanage.backend.auth.dto.MeResponse;
import com.intermanage.backend.auth.dto.UserResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;

@Mapper
public interface UserMapper {

    @Mapping(target = "roleName", expression = "java(user.isOwner() ? user.getRole().getName() : null)")
    @Mapping(target = "owner", expression = "java(user.isOwner())")
    @Mapping(target = "permissions", expression = "java(unionPermissions(user, stores))")
    @Mapping(target = "storePermissions", expression = "java(storePermissions(user, stores))")
    @Mapping(target = "storeRoles", expression = "java(storeRoleNames(user, stores))")
    @Mapping(target = "taskAssignStores", expression = "java(taskAssignStores(user, stores))")
    @Mapping(target = "stores", source = "stores")
    @Mapping(target = "sidebarOrders", source = "sidebarOrders")
    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "background", source = "background")
    @Mapping(target = "avatar", source = "avatar")
    @Mapping(target = "idleLockMinutes", source = "idleLockMinutes")
    @Mapping(target = "canAssignTasks", expression = "java(user.mayAssignTasks())")
    MeResponse toMeResponse(AppUser user, List<StoreSummary> stores, Map<UUID, List<String>> sidebarOrders,
            BackgroundResponse background, AvatarResponse avatar, Integer idleLockMinutes);

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "roleName", expression = "java(rankSummary(user))")
    @Mapping(target = "owner", expression = "java(user.isOwner())")
    @Mapping(target = "storeRoles", expression = "java(storeRoleIds(user))")
    @Mapping(target = "storeIds", source = "user.stores")
    @Mapping(target = "positionName", source = "positionName")
    UserResponse toResponse(AppUser user, String positionName);

    default List<UUID> storeIds(Collection<Store> stores) {
        return stores.stream().map(Store::getId).sorted().toList();
    }

    /** Uprawnienia w każdym dostępnym sklepie: ranga konta, właściciel wszystko. */
    default Map<UUID, Map<PermissionCategory, PermissionLevel>> storePermissions(AppUser user,
            List<StoreSummary> stores) {
        Map<UUID, Map<PermissionCategory, PermissionLevel>> result = new LinkedHashMap<>();
        for (StoreSummary store : stores) {
            if (user.isOwner()) {
                result.put(store.id(), user.getRole().effectivePermissions());
            } else {
                StoreRole rank = user.rankIn(store.id());
                result.put(store.id(), rank == null ? Map.of() : rank.effectivePermissions());
            }
        }
        return result;
    }

    /** Suma uprawnień ze wszystkich sklepów (zgodność ze starszą aplikacją mobilną). */
    default Map<PermissionCategory, PermissionLevel> unionPermissions(AppUser user, List<StoreSummary> stores) {
        if (user.isOwner()) {
            return user.getRole().effectivePermissions();
        }
        Map<PermissionCategory, PermissionLevel> result = new EnumMap<>(PermissionCategory.class);
        storePermissions(user, stores).values().forEach(permissions -> permissions.forEach((category, level) ->
                result.merge(category, level, (a, b) -> a.includes(b) ? a : b)));
        return result;
    }

    default Map<UUID, String> storeRoleNames(AppUser user, List<StoreSummary> stores) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (StoreSummary store : stores) {
            String name = user.rankNameIn(store.id());
            if (name != null) {
                result.put(store.id(), name);
            }
        }
        return result;
    }

    /** Sklepy, w których konto zleca zadania: właściciel wszędzie, inni z włączonym zlecaniem albo kimś pod sobą. */
    default List<UUID> taskAssignStores(AppUser user, List<StoreSummary> stores) {
        return stores.stream().map(StoreSummary::id).filter(storeId -> {
            if (user.mayAssignTasks()) {
                return true;
            }
            StoreRole rank = user.rankIn(storeId);
            return rank != null && (rank.isAdmin() || (!rank.isOutsideHierarchy() && rank.getLowerRanks() > 0));
        }).toList();
    }

    /** Ranga w każdym przypisanym sklepie (sklep → ranga). */
    default Map<UUID, UUID> storeRoleIds(AppUser user) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        if (user.isOwner()) {
            return result;
        }
        for (Store store : user.getStores()) {
            StoreRole rank = user.rankIn(store.getId());
            if (rank != null) {
                result.put(store.getId(), rank.getId());
            }
        }
        return result;
    }

    /** Rangi do listy kont: „Kierownik”, a przy różnych w sklepach „Kierownik, Pracownik”. */
    default String rankSummary(AppUser user) {
        if (user.isOwner()) {
            return user.getRole().getName();
        }
        Set<String> names = new LinkedHashSet<>();
        user.getStores().stream().sorted(Comparator.comparing(Store::getNumber)).forEach(store -> {
            StoreRole rank = user.rankIn(store.getId());
            if (rank != null) {
                names.add(rank.getName());
            }
        });
        return names.isEmpty() ? null : String.join(", ", names);
    }
}
