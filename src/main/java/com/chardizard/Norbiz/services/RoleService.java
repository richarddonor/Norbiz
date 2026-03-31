package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Permission;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    public List<Role> findAll() {
        return roleRepository.findAll();
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
        return roleRepository.save(role);
    }

    public Role update(Long id, String name, String displayName, Set<Long> permissionIds) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
        role.setName(name);
        role.setDisplayName(displayName);
        role.setPermissions(resolvePermissions(permissionIds));
        return roleRepository.save(role);
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
