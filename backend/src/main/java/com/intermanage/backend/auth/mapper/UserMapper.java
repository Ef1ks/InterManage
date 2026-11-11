package com.intermanage.backend.auth.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.auth.dto.AvatarResponse;
import com.intermanage.backend.auth.dto.BackgroundResponse;
import com.intermanage.backend.auth.dto.MeResponse;
import com.intermanage.backend.auth.dto.UserResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;

@Mapper
public interface UserMapper {

    @Mapping(target = "roleName", source = "user.role.name")
    @Mapping(target = "owner", source = "user.role.owner")
    @Mapping(target = "permissions", expression = "java(user.getRole().effectivePermissions())")
    @Mapping(target = "stores", source = "stores")
    @Mapping(target = "sidebarOrders", source = "sidebarOrders")
    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "background", source = "background")
    @Mapping(target = "avatar", source = "avatar")
    MeResponse toMeResponse(AppUser user, List<StoreSummary> stores, Map<UUID, List<String>> sidebarOrders,
            BackgroundResponse background, AvatarResponse avatar);

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "roleId", source = "user.role.id")
    @Mapping(target = "roleName", source = "user.role.name")
    @Mapping(target = "owner", source = "user.role.owner")
    @Mapping(target = "storeIds", source = "user.stores")
    @Mapping(target = "positionName", source = "positionName")
    UserResponse toResponse(AppUser user, String positionName);

    default List<UUID> storeIds(Collection<Store> stores) {
        return stores.stream().map(Store::getId).sorted().toList();
    }
}
