package com.chardizard.Norbiz.config;

import com.chardizard.Norbiz.models.Permission;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import com.chardizard.Norbiz.repositories.RoleRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Permissions
        Permission readPermission = permissionRepository.findByName("READ")
                .orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName("READ");
                    return permissionRepository.save(p);
                });

        Permission writePermission = permissionRepository.findByName("WRITE")
                .orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName("WRITE");
                    return permissionRepository.save(p);
                });

        Permission deletePermission = permissionRepository.findByName("DELETE")
                .orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName("DELETE");
                    return permissionRepository.save(p);
                });

        Permission manageSystemPermission = permissionRepository.findByName("MANAGE_SYSTEM")
                .orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName("MANAGE_SYSTEM");
                    return permissionRepository.save(p);
                });

        Permission createUserPermission = permissionRepository.findByName("CREATE_USER")
                .orElseGet(() -> {
                    Permission p = new Permission();
                    p.setName("CREATE_USER");
                    return permissionRepository.save(p);
                });

        // Roles
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("ADMIN");
                    r.setPermissions(Set.of(readPermission, writePermission));
                    return roleRepository.save(r);
                });

        // SYSTEM_ADMIN: business-level access (read, write, delete, create users — no system management)
        Role systemAdminRole = roleRepository.findByName("SYSTEM_ADMIN")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("SYSTEM_ADMIN");
                    r.setPermissions(Set.of(readPermission, writePermission, deletePermission, createUserPermission));
                    return roleRepository.save(r);
                });

        // SUPER_ADMIN: complete access including system management
        Role superAdminRole = roleRepository.findByName("SUPER_ADMIN")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("SUPER_ADMIN");
                    r.setPermissions(Set.of(readPermission, writePermission, deletePermission, manageSystemPermission, createUserPermission));
                    return roleRepository.save(r);
                });

        // Seed users (skip if already present)
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@norbiz.com");
            admin.setPassword(passwordEncoder.encode("password"));
            admin.setRoles(Set.of(adminRole));
            userRepository.save(admin);
        }

        if (userRepository.findByUsername("super_admin").isEmpty()) {
            User superAdmin = new User();
            superAdmin.setUsername("super_admin");
            superAdmin.setEmail("super.admin@norbiz.com");
            superAdmin.setPassword(passwordEncoder.encode("password"));
            superAdmin.setRoles(Set.of(superAdminRole));
            userRepository.save(superAdmin);
        }

        if (userRepository.findByUsername("system_admin").isEmpty()) {
            User systemAdmin = new User();
            systemAdmin.setUsername("system_admin");
            systemAdmin.setEmail("system.admin@norbiz.com");
            systemAdmin.setPassword(passwordEncoder.encode("password"));
            systemAdmin.setRoles(Set.of(systemAdminRole));
            userRepository.save(systemAdmin);
        }
    }
}