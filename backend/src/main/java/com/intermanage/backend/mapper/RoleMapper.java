package com.intermanage.backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.intermanage.backend.dto.RoleResponse;
import com.intermanage.backend.entity.Role;

@Mapper
public interface RoleMapper {

    /** Rola właściciela ma wszystkie uprawnienia, więc bierzemy uprawnienia efektywne, nie zapisane w bazie. */
    @Mapping(target = "permissions", expression = "java(role.effectivePermissions())")
    RoleResponse toResponse(Role role, long userCount);
}
