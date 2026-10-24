package com.intermanage.backend.role;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.ApiException;
import com.intermanage.backend.permission.PermissionCategory;
import com.intermanage.backend.permission.PermissionLevel;
import com.intermanage.backend.user.AppUserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final AppUserRepository users;

    public RoleService(RoleRepository roles, AppUserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    public record RoleRequest(
            @NotBlank(message = "Podaj nazwę roli")
            @Size(max = 100, message = "Nazwa może mieć maksymalnie 100 znaków") String name,
            @Size(max = 500, message = "Opis może mieć maksymalnie 500 znaków") String description,
            @NotNull(message = "Podaj uprawnienia") Map<PermissionCategory, PermissionLevel> permissions) {
    }

    public record RoleResponse(
            Long id,
            String name,
            String description,
            boolean owner,
            Map<PermissionCategory, PermissionLevel> permissions,
            long userCount,
            Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : users.countUsersPerRole()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return roles.findAllWithPermissions().stream()
                .map(role -> toResponse(role, counts.getOrDefault(role.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(Long id) {
        Role role = load(id);
        return toResponse(role, users.countByRoleId(id));
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        String name = request.name().trim();
        if (roles.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("Rola o tej nazwie już istnieje");
        }
        Role role = new Role(name, blankToNull(request.description()));
        role.replacePermissions(withoutNulls(request.permissions()));
        return toResponse(roles.save(role), 0);
    }

    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        Role role = loadEditable(id);
        String name = request.name().trim();
        if (roles.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw ApiException.conflict("Rola o tej nazwie już istnieje");
        }
        role.setName(name);
        role.setDescription(blankToNull(request.description()));
        role.replacePermissions(withoutNulls(request.permissions()));
        return toResponse(role, users.countByRoleId(id));
    }

    @Transactional
    public void delete(Long id) {
        Role role = loadEditable(id);
        long assigned = users.countByRoleId(id);
        if (assigned > 0) {
            throw ApiException.conflict(
                    "Nie można usunąć roli przypisanej do użytkowników (" + assigned + "). Najpierw zmień im rolę.");
        }
        roles.delete(role);
    }

    private Role loadEditable(Long id) {
        Role role = load(id);
        if (role.isOwner()) {
            throw ApiException.conflict("Roli Właściciel nie można edytować ani usuwać");
        }
        return role;
    }

    private Role load(Long id) {
        return roles.findWithPermissionsById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono roli"));
    }

    private static RoleResponse toResponse(Role role, long userCount) {
        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), role.isOwner(),
                role.effectivePermissions(), userCount, role.getCreatedAt());
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
