package com.intermanage.backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.RoleRequest;
import com.intermanage.backend.dto.RoleResponse;
import com.intermanage.backend.entity.PermissionCategory;
import com.intermanage.backend.entity.PermissionLevel;
import com.intermanage.backend.entity.Role;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.RoleMapper;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.repository.RoleRepository;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final RoleMapper roleMapper;

    public RoleService(RoleRepository roles, AppUserRepository users, RoleMapper roleMapper) {
        this.roles = roles;
        this.users = users;
        this.roleMapper = roleMapper;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : users.countUsersPerRole()) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return roles.findAllWithPermissions().stream()
                .map(role -> roleMapper.toResponse(role, counts.getOrDefault(role.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(UUID id) {
        Role role = load(id);
        return roleMapper.toResponse(role, users.countByRoleId(id));
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        String name = request.name().trim();
        if (roles.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("Rola o tej nazwie już istnieje");
        }
        Role role = new Role(name, blankToNull(request.description()));
        role.replacePermissions(withoutNulls(request.permissions()));
        return roleMapper.toResponse(roles.save(role), 0);
    }

    @Transactional
    public RoleResponse update(UUID id, RoleRequest request) {
        Role role = loadEditable(id);
        String name = request.name().trim();
        if (roles.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw ApiException.conflict("Rola o tej nazwie już istnieje");
        }
        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        role.replacePermissions(withoutNulls(request.permissions()));
        return roleMapper.toResponse(role, users.countByRoleId(id));
    }

    @Transactional
    public void delete(UUID id) {
        Role role = loadEditable(id);
        long assigned = users.countByRoleId(id);
        if (assigned > 0) {
            throw ApiException.conflict(
                    "Nie można usunąć roli przypisanej do użytkowników (" + assigned + "). Najpierw zmień im rolę.");
        }
        roles.delete(role);
    }

    private Role loadEditable(UUID id) {
        Role role = load(id);
        if (role.isOwner()) {
            throw ApiException.conflict("Roli Właściciel nie można edytować ani usuwać");
        }
        return role;
    }

    private Role load(UUID id) {
        return roles.findWithPermissionsById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono roli"));
    }

    private static Map<PermissionCategory, PermissionLevel> withoutNulls(Map<PermissionCategory, PermissionLevel> in) {
        Map<PermissionCategory, PermissionLevel> result = new HashMap<>();
        in.forEach((category, level) -> {
            if (category != null && level != null) {
                result.put(category, level);
            }
        });
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
