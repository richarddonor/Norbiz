package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Permission;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    public Page<Role> findAll(Map<String, String> filters, Pageable pageable) {
        Specification<Role> spec = SpecificationUtils.allOf(
                SpecificationUtils.containsIgnoreCase("displayName", filters.get("displayName")),
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("permissions.name", filters.get("permissions"))
        );
        return roleRepository.findAll(spec, pageable);
    }

    public Role findById(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
    }

    public Role create(String name, String displayName, Set<Long> permissionIds) {
        Role role = new Role();
        role.setName(name);
        role.setDisplayName(displayName);
        role.setPermissions(resolvePermissions(permissionIds));
        Role saved = roleRepository.save(role);
        log.info("Created role '{}' (id={}) with {} permission(s)", saved.getName(), saved.getId(), saved.getPermissions().size());
        return saved;
    }

    public Role update(Long id, String name, String displayName, Set<Long> permissionIds) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
        role.setName(name);
        role.setDisplayName(displayName);
        role.setPermissions(resolvePermissions(permissionIds));
        Role saved = roleRepository.save(role);
        log.info("Updated role '{}' (id={}) with {} permission(s)", saved.getName(), saved.getId(), saved.getPermissions().size());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
        userRepository.findByRolesContaining(role).forEach(user -> {
            user.getRoles().remove(role);
            userRepository.save(user);
        });
        roleRepository.delete(role);
        log.info("Deleted role '{}' (id={})", role.getName(), id);
    }

    private Set<Permission> resolvePermissions(Set<Long> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return new HashSet<>();
        }
        List<Permission> found = permissionRepository.findAllById(permissionIds);
        if (found.size() != permissionIds.size()) {
            throw new IllegalArgumentException("One or more permission IDs are invalid");
        }
        return new HashSet<>(found);
    }
}
