package com.intermanage.backend.auth.mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

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
    MeResponse toMeResponse(AppUser user, List<StoreSummary> stores);

    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", source = "role.name")
    @Mapping(target = "owner", source = "role.owner")
    @Mapping(target = "storeIds", source = "stores")
    UserResponse toResponse(AppUser user);

    default List<UUID> storeIds(Collection<Store> stores) {
        return stores.stream().map(Store::getId).sorted().toList();
    }
}
